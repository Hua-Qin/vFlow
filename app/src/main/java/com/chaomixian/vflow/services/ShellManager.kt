// 文件路径: main/java/com/chaomixian/vflow/services/ShellManager.kt
package com.chaomixian.vflow.services

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.view.Surface
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.core.logging.LogManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shell 管理工具类 (原 ShizukuManager)。
 * 负责管理与 Shizuku 服务的连接，并提供核心的 Shell 命令执行功能。
 * 支持 Shizuku 和 Root 两种执行后端，并可根据设置自动切换。
 */
object ShellManager {
    private const val TAG = "vFlowShellManager"
    // MIUI/EMUI 等受限 ROM 首次绑定 Shizuku UserService 时需拉起 :vflow_shizuku 进程，
    // 3s 远不够（实测常需 5-10s），导致 onServiceConnected 在 3s 内未回调而超时失败。放宽到 15s。
    private const val BIND_TIMEOUT_MS = 15000L
    private const val MAX_RETRY_COUNT = 3
    private const val DEPLOY_SUCCESS_MARKER = "__VFLOW_DEPLOY_OK__"
    private val ROOT_SHELL_COMMANDS = arrayOf("su", "-mm", "-c", "sh")
    private val ROOT_SHELL_FALLBACK_COMMANDS = arrayOf("su", "-c", "sh")

    /**
     * Shell 执行模式
     */
    enum class ShellMode {
        AUTO,      // 自动：根据用户在设置中的偏好决定
        SHIZUKU,   // 强制使用 Shizuku
        ROOT,      // 强制使用 Root
        CORE,      // 强制使用 vFlow Core Shell worker
        CORE_ROOT  // 强制使用 vFlow Core Root worker
    }

    data class ExecutableDeployResult(
        val targetFile: File,
        val success: Boolean,
        val output: String
    )

    data class ShellCommandResult(
        val output: String,
        val exitCode: Int,
        val success: Boolean
    )

    @Volatile
    private var shellService: IShizukuUserService? = null

    // 使用 Mutex 替代 AtomicBoolean 来保护 Shizuku 连接过程
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 动态获取所需的权限。
     * 模块可以调用此方法来声明它们需要的权限，而无需自己判断是 Root 还是 Shizuku。
     * @param context 上下文，用于读取 SharedPreferences。
     * @return 包含 PermissionManager.ROOT 或 PermissionManager.SHIZUKU 的列表。
     */
    fun getRequiredPermissions(context: Context): List<Permission> {
        val prefs = context.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        val defaultMode = prefs.getString("default_shell_mode", "shizuku")
        return when (defaultMode) {
            "root" -> listOf(PermissionManager.ROOT)
            "core" -> listOf(PermissionManager.CORE)
            "core_root" -> listOf(PermissionManager.CORE_ROOT)
            else -> listOf(PermissionManager.SHIZUKU)
        }
    }

    /**
     * 主动预连接 Shizuku 服务。
     * 在应用启动时调用此方法，可以在后台异步建立连接，
     * 以便在用户首次执行相关模块时获得更快的响应速度。
     */
    fun proactiveConnect(context: Context) {
        // 如果服务已连接并且 binder 存活，则无需操作
        if (shellService?.asBinder()?.isBinderAlive == true) {
            DebugLogger.d(TAG, "Shizuku 服务已连接，跳过预连接。")
            return
        }

        // 仅当用户偏好不是「纯 ROOT」时才去预连接 Shizuku。
        // 明确选 ROOT 的用户不需要 Shizuku，白等 15s×3 次绑定重试是纯浪费。
        val prefs = context.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        val coreMode = prefs.getString("preferred_core_launch_mode", "shizuku")
        val defaultShellMode = prefs.getString("default_shell_mode", "shizuku")
        val explicitlyRoot = (coreMode == "root") && (defaultShellMode == "root")
        if (explicitlyRoot) {
            DebugLogger.d(TAG, "偏好为 ROOT 模式，跳过 Shizuku 预连接。")
            return
        }

        DebugLogger.d(TAG, "发起 Shizuku 服务预连接...")
        // 启动一个后台协程来执行连接，不阻塞主线程
        scope.launch {
            if (isShizukuActive(context)) {
                // 调用 getService 来触发带重试的连接逻辑
                getService(context)
                DebugLogger.d(TAG, "Shizuku 预连接尝试完成。")
            } else {
                DebugLogger.d(TAG, "Shizuku 未激活，跳过预连接。")
            }
        }
    }

    /**
     * 检查 Shizuku 是否可用且已授权。
     */
    fun isShizukuActive(context: Context): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // isRootAvailable 结果缓存：避免在短时间内多次触发 su 弹窗或 PermissionDenied
    @Volatile
    private var cachedRootAvailable: Pair<Boolean, Long>? = null
    private const val ROOT_CACHE_MS = 10_000L
    private const val ROOT_CHECK_TIMEOUT_MS = 5_000L

    /**
     * 检查 Root 权限是否可用。
     * 尝试执行 'su' 命令并检查退出码。
     *
     * 增强：
     * - 5s 超时（防止 su 弹窗未回应时永久阻塞）
     * - 10s 结果缓存（防止刷屏 su 弹窗/重复 PermissionDenied 日志）
     * - 失败时把具体原因写入 DebugLogger（Permission denied vs 超时 vs su 不存在）
     */
    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < ROOT_CACHE_MS) return cached
        }
        val result = try {
            val process = try {
                createRootShellProcess()
            } catch (t: Throwable) {
                // 最常见的失败：Runtime.exec("su -mm ...") -> IOException("Cannot run program \"su\": error=13, Permission denied"
                // 或 SecurityException（MIUI 进程管理拦截）
                DebugLogger.w(TAG, "isRootAvailable: 无法启动 su 进程: ${t.javaClass.simpleName}: ${t.message}")
                return cacheAndReturn(false)
            }
            try {
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("exit\n")
                os.flush()
            } catch (t: Throwable) {
                DebugLogger.w(TAG, "isRootAvailable: 写入 exit 指令失败 (su 管道已断？): ${t.message}")
            }
            val finishedInTime = try {
                process.waitFor(ROOT_CHECK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) { false }
            if (!finishedInTime) {
                DebugLogger.w(TAG, "isRootAvailable: su 命令在 ${ROOT_CHECK_TIMEOUT_MS}ms 内未退出（大概率是 su 授权弹窗没点或 Magisk/KernelSU 无响应）。判定为失败，缓存 10s 避免重复等待。")
                try { process.destroy() } catch (_: Exception) {}
                return cacheAndReturn(false)
            }
            val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { -1 }
            if (exit == 0) {
                cacheAndReturn(true)
            } else {
                DebugLogger.w(TAG, "isRootAvailable: su 退出码非 0 (exit=$exit)。su 可能已被拒绝或不可用。")
                cacheAndReturn(false)
            }
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "isRootAvailable: 未预期异常: ${t.javaClass.simpleName}: ${t.message}")
            cacheAndReturn(false)
        }
        return result
    }

    private fun cacheAndReturn(value: Boolean): Boolean {
        cachedRootAvailable = value to System.currentTimeMillis()
        return value
    }

    /**
     * 启动守护任务 (目前仅支持 Shizuku 模式)
     */
    fun startWatcher(context: Context) {
        // 守护任务依赖于 IShizukuUserService 的长连接，Root 模式下暂不支持此机制
        if (!isShizukuActive(context)) {
            DebugLogger.w(TAG, "Shizuku 未激活，无法启动守护任务。")
            return
        }

        scope.launch {
            val service = getService(context)
            if (service == null) {
                DebugLogger.e(TAG, "无法启动守护任务：Shizuku 服务连接失败。")
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    service.startWatcher(context.packageName, TriggerService::class.java.name)
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "启动守护任务时出错。", e)
            }
        }
    }

    /**
     * 停止守护任务 (目前仅支持 Shizuku 模式)
     */
    fun stopWatcher(context: Context) {
        if (!isShizukuActive(context)) return

        scope.launch {
            val service = getService(context)
            if (service == null) {
                // 如果服务未连接，也无需停止
                return@launch
            }
            try {
                withContext(Dispatchers.IO) {
                    service.stopWatcher()
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "停止守护任务时出错。", e)
            }
        }
    }

    /**
     * 创建虚拟屏幕 (仅 Shizuku 模式)
     */
    suspend fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        val context = LogManager.applicationContext
        if (!isShizukuActive(context)) return -1

        val service = getService(context) ?: return -1
        return try {
            service.createVirtualDisplay(surface, width, height, dpi)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "调用 createVirtualDisplay 失败", e)
            -1
        }
    }

    /**
     * 销毁虚拟屏幕
     */
    suspend fun destroyVirtualDisplay(displayId: Int) {
        if (displayId == -1) return
        val context = LogManager.applicationContext
        val service = getService(context) ?: return
        try {
            service.destroyVirtualDisplay(displayId)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "调用 destroyVirtualDisplay 失败", e)
        }
    }

    /**
     * 执行一条 Shell 命令。
     * @param context 上下文
     * @param command 要执行的命令
     * @param mode 执行模式，默认为 AUTO (自动根据设置选择)
     * @return 命令输出字符串，或以 "Error:" 开头的错误信息。
     */
    suspend fun execShellCommand(
        context: Context,
        command: String,
        mode: ShellMode = ShellMode.AUTO
    ): String {
        return execShellCommandWithResult(context, command, mode).output
    }

    suspend fun execShellCommandWithResult(
        context: Context,
        command: String,
        mode: ShellMode = ShellMode.AUTO
    ): ShellCommandResult {
        return withContext(Dispatchers.IO) {
            // 确定最终使用的模式
            val finalMode = if (mode == ShellMode.AUTO) {
                val prefs = context.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
                val defaultMode = prefs.getString("default_shell_mode", "shizuku")
                when (defaultMode) {
                    "root" -> ShellMode.ROOT
                    "core" -> ShellMode.CORE
                    "core_root" -> ShellMode.CORE_ROOT
                    else -> ShellMode.SHIZUKU
                }
            } else {
                mode
            }

            // 根据模式分发执行
            when (finalMode) {
                ShellMode.ROOT -> executeRootCommand(command)
                ShellMode.CORE -> executeCoreCommand(context, command, VFlowCoreBridge.ExecMode.SHELL)
                ShellMode.CORE_ROOT -> executeCoreCommand(context, command, VFlowCoreBridge.ExecMode.ROOT)
                else -> executeShizukuCommand(context, command)
            }
        }
    }

    /**
     * 通过 shell 将已暂存的可执行文件部署到目标路径，并在 shell 侧完成可执行校验。
     * 不能使用应用进程的 File.exists()/canExecute() 检查 /data/local/tmp 这类 shell 可见目录。
     */
    suspend fun deployExecutableViaShell(
        context: Context,
        stagedFile: File,
        targetPath: String,
        mode: ShellMode = ShellMode.AUTO,
        cleanupStagedFile: Boolean = true
    ): ExecutableDeployResult {
        val targetFile = File(targetPath)
        val targetDir = targetFile.parentFile
        if (targetDir == null) {
            return ExecutableDeployResult(
                targetFile = targetFile,
                success = false,
                output = "Error: Invalid target path"
            )
        }

        return try {
            val command = buildString {
                append("mkdir -p ")
                append(shellQuote(targetDir.absolutePath))
                append(" && cp ")
                append(shellQuote(stagedFile.absolutePath))
                append(' ')
                append(shellQuote(targetFile.absolutePath))
                append(" && chmod 755 ")
                append(shellQuote(targetFile.absolutePath))
                append(" && [ -f ")
                append(shellQuote(targetFile.absolutePath))
                append(" ] && [ -x ")
                append(shellQuote(targetFile.absolutePath))
                append(" ] && printf ")
                append(shellQuote(DEPLOY_SUCCESS_MARKER))
            }

            val output = execShellCommand(context, command, mode)
            val success = !output.startsWith("Error:", ignoreCase = true) && output.contains(DEPLOY_SUCCESS_MARKER)
            val normalizedOutput = when {
                success -> "Success"
                output.startsWith("Error:", ignoreCase = true) -> output
                else -> "Error: Shell verification failed"
            }

            ExecutableDeployResult(
                targetFile = targetFile,
                success = success,
                output = normalizedOutput
            )
        } finally {
            if (cleanupStagedFile) {
                stagedFile.delete()
            }
        }
    }

    /**
     * 内部 Root 执行逻辑
     */
    private fun executeRootCommand(command: String): ShellCommandResult {
        return try {
            val process = createRootShellProcess()
            val os = DataOutputStream(process.outputStream)

            // 写入命令和退出指令
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            // 读取输出
            // use 块会自动关闭流
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ShellCommandResult(
                    output = if (stdout.isNotEmpty()) stdout.trim() else "Success",
                    exitCode = exitCode,
                    success = true
                )
            } else {
                // 优先返回 stderr，如果没有则返回 stdout，最后返回错误码
                val msg = if (stderr.isNotBlank()) stderr else if (stdout.isNotBlank()) stdout else "Exit code $exitCode"
                ShellCommandResult(
                    output = "Error: ${msg.trim()}",
                    exitCode = exitCode,
                    success = false
                )
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Root execution failed", e)
            ShellCommandResult(
                output = "Error: ${e.message}",
                exitCode = -1,
                success = false
            )
        }
    }

    private fun createRootShellProcess(): Process {
        return try {
            Runtime.getRuntime().exec(ROOT_SHELL_COMMANDS)
        } catch (e: Exception) {
            DebugLogger.w(TAG, "Failed to start root shell with su -mm, fallback to plain su", e)
            Runtime.getRuntime().exec(ROOT_SHELL_FALLBACK_COMMANDS)
        }
    }

    private suspend fun executeCoreCommand(
        context: Context,
        command: String,
        mode: VFlowCoreBridge.ExecMode
    ): ShellCommandResult {
        return try {
            if (!VFlowCoreBridge.connect(context)) {
                return ShellCommandResult(
                    output = "Error: vFlow Core 未运行或连接失败",
                    exitCode = -1,
                    success = false
                )
            }

            if (mode == VFlowCoreBridge.ExecMode.ROOT &&
                VFlowCoreBridge.privilegeMode != VFlowCoreBridge.PrivilegeMode.ROOT
            ) {
                return ShellCommandResult(
                    output = "Error: vFlow Core 未以 Root 权限运行",
                    exitCode = -1,
                    success = false
                )
            }

            val result = VFlowCoreBridge.execWithResult(command, mode, context)
            ShellCommandResult(
                output = result.output,
                exitCode = result.exitCode,
                success = result.success
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Core execution failed", e)
            ShellCommandResult(
                output = "Error: ${e.message}",
                exitCode = -1,
                success = false
            )
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    /**
     * 内部 Shizuku 执行逻辑 (保留原有逻辑)
     */
    private suspend fun executeShizukuCommand(context: Context, command: String): ShellCommandResult {
        val service = getService(context)
        if (service == null) {
            val status = if (!isShizukuActive(context)) "Shizuku 未激活或未授权" else "服务连接失败"
            return ShellCommandResult(
                output = "Error: $status",
                exitCode = -1,
                success = false
            )
        }
        return try {
            val result = service.execWithResult(command)
            ShellCommandResult(
                output = result.getString("output").orEmpty(),
                exitCode = result.getInt("exitCode", -1),
                success = result.getBoolean("success", false)
            )
        } catch (e: CancellationException) {
            // 捕获协程取消异常。
            DebugLogger.d(TAG, "Shizuku command execution was cancelled as expected.")
            throw e
        } catch (e: Exception) {
            // 其他所有类型的异常仍然被视为执行失败。
            DebugLogger.e(TAG, "执行命令失败，连接可能已丢失。", e)
            shellService = null
            ShellCommandResult(
                output = "Error: ${e.message}",
                exitCode = -1,
                success = false
            )
        }
    }

    /**
     * 开启无障碍服务。
     * 优先使用 WRITE_SECURE_SETTINGS 直接写入，失败后使用 AUTO Shell 模式。
     * @return 返回操作是否成功。
     */
    suspend fun enableAccessibilityService(context: Context): Boolean {
        if (enableAccessibilityServiceViaSecureSettings(context)) {
            return true
        }

        val serviceName = AccessibilityServiceStatus.getServiceId(context)
        // 1. 读取当前已启用的服务列表
        val currentServices = execShellCommand(context, "settings get secure enabled_accessibility_services")
        if (currentServices.startsWith("Error:")) {
            DebugLogger.e(TAG, "读取无障碍服务列表失败: $currentServices")
            return false
        }

        // 2. 检查服务是否已在列表中
        if (currentServices.split(':').any { it.equals(serviceName, ignoreCase = true) }) {
            DebugLogger.d(TAG, "无障碍服务已经启用。")
            return true
        }

        // 3. 将我们的服务添加到列表中
        val newServices = if (currentServices.isBlank() || currentServices == "null") {
            serviceName
        } else {
            "$currentServices:$serviceName"
        }

        // 4. 写回新的服务列表
        val result = execShellCommand(context, "settings put secure enabled_accessibility_services '$newServices'")
        if (result.startsWith("Error:")) {
            DebugLogger.e(TAG, "写入无障碍服务列表失败: $result")
            return false
        }

        // 5. 确保无障碍总开关是打开的
        execShellCommand(context, "settings put secure accessibility_enabled 1")
        DebugLogger.d(TAG, "已通过 Shell 尝试启用无障碍服务。")
        return true
    }

    suspend fun ensureAccessibilityServiceRunning(context: Context): Boolean {
        if (AccessibilityServiceStatus.isRunning(context)) {
            return true
        }

        return if (AccessibilityServiceStatus.isEnabledInSettings(context)) {
            DebugLogger.w(TAG, "无障碍服务在设置中已启用但未正常运行，尝试强制重载。")
            restartAccessibilityService(context)
        } else {
            enableAccessibilityService(context)
        }
    }

    /**
     * 关闭无障碍服务。
     * 优先使用 WRITE_SECURE_SETTINGS 直接写入，失败后使用 AUTO Shell 模式。
     * @return 返回操作是否成功。
     */
    suspend fun disableAccessibilityService(context: Context): Boolean {
        if (disableAccessibilityServiceViaSecureSettings(context)) {
            return true
        }

        val serviceName = AccessibilityServiceStatus.getServiceId(context)
        // 1. 读取当前服务列表
        val currentServices = execShellCommand(context, "settings get secure enabled_accessibility_services")
        if (currentServices.startsWith("Error:") || currentServices == "null" || currentServices.isBlank()) {
            return true // 列表为空，无需操作
        }

        // 2. 从列表中移除我们的服务
        val serviceList = currentServices.split(':').toMutableList()
        val removed = serviceList.removeAll { it.equals(serviceName, ignoreCase = true) }

        if (!removed) {
            return true // 服务本就不在列表中，无需操作
        }

        // 3. 写回新的服务列表
        val newServices = serviceList.joinToString(":")
        val result = execShellCommand(context, "settings put secure enabled_accessibility_services '$newServices'")
        if (result.startsWith("Error:")) {
            DebugLogger.e(TAG, "移除无障碍服务失败: $result")
            return false
        }
        DebugLogger.d(TAG, "已通过 Shell 尝试禁用无障碍服务。")
        return true
    }

    private suspend fun restartAccessibilityService(context: Context): Boolean {
        if (!disableAccessibilityService(context)) {
            return false
        }
        delay(400)
        return enableAccessibilityService(context)
    }

    private fun canWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun enableAccessibilityServiceViaSecureSettings(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) {
            return false
        }

        return try {
            val serviceName = AccessibilityServiceStatus.getServiceId(context)
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val serviceList = currentServices
                .orEmpty()
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            if (serviceList.none { it.equals(serviceName, ignoreCase = true) }) {
                serviceList += serviceName
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    serviceList.joinToString(":")
                )
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            DebugLogger.d(TAG, "已通过 WRITE_SECURE_SETTINGS 启用无障碍服务。")
            true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "通过 WRITE_SECURE_SETTINGS 启用无障碍服务失败，回退到 Shell。", e)
            false
        }
    }

    private fun disableAccessibilityServiceViaSecureSettings(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) {
            return false
        }

        return try {
            val serviceName = AccessibilityServiceStatus.getServiceId(context)
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (currentServices.isNullOrBlank()) {
                return true
            }

            val serviceList = currentServices
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            val removed = serviceList.removeAll { it.equals(serviceName, ignoreCase = true) }
            if (!removed) {
                return true
            }

            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                serviceList.joinToString(":")
            )
            if (serviceList.isEmpty()) {
                Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
            DebugLogger.d(TAG, "已通过 WRITE_SECURE_SETTINGS 禁用无障碍服务。")
            true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "通过 WRITE_SECURE_SETTINGS 禁用无障碍服务失败，回退到 Shell。", e)
            false
        }
    }

    suspend fun migrateAccessibilityServiceSetting(context: Context, disguisedEnabled: Boolean): Boolean {
        if (migrateAccessibilityServiceSettingViaSecureSettings(context, disguisedEnabled)) {
            return true
        }

        if (!isShizukuActive(context) && !isRootAvailable()) {
            DebugLogger.w(TAG, "跳过无障碍服务设置迁移：Shizuku/Root 不可用")
            return false
        }

        val currentServices = execShellCommand(context, "settings get secure enabled_accessibility_services")
        if (currentServices.startsWith("Error:")) {
            DebugLogger.e(TAG, "读取无障碍服务列表失败: $currentServices")
            return false
        }

        val originalServiceId = AccessibilityServiceStatus.getOriginalServiceId(context)
        val disguisedServiceId = AccessibilityServiceStatus.getDisguisedServiceId(context)
        val targetServiceId = if (disguisedEnabled) disguisedServiceId else originalServiceId
        val sourceServiceId = if (disguisedEnabled) originalServiceId else disguisedServiceId
        val migratedServices = AccessibilityServiceStatus.replaceServiceId(
            enabledServicesSetting = currentServices.takeUnless { it == "null" },
            fromServiceId = sourceServiceId,
            toServiceId = targetServiceId
        )

        if (migratedServices == currentServices) {
            return true
        }

        val result = execShellCommand(
            context,
            "settings put secure enabled_accessibility_services '$migratedServices'"
        )
        if (result.startsWith("Error:")) {
            DebugLogger.e(TAG, "迁移无障碍服务列表失败: $result")
            return false
        }

        execShellCommand(context, "settings put secure accessibility_enabled 1")
        DebugLogger.d(TAG, "已通过 Shell 迁移无障碍服务设置。")
        return true
    }

    private fun migrateAccessibilityServiceSettingViaSecureSettings(
        context: Context,
        disguisedEnabled: Boolean
    ): Boolean {
        if (!canWriteSecureSettings(context)) {
            return false
        }

        return try {
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val originalServiceId = AccessibilityServiceStatus.getOriginalServiceId(context)
            val disguisedServiceId = AccessibilityServiceStatus.getDisguisedServiceId(context)
            val targetServiceId = if (disguisedEnabled) disguisedServiceId else originalServiceId
            val sourceServiceId = if (disguisedEnabled) originalServiceId else disguisedServiceId
            val migratedServices = AccessibilityServiceStatus.replaceServiceId(
                enabledServicesSetting = currentServices,
                fromServiceId = sourceServiceId,
                toServiceId = targetServiceId
            )

            if (migratedServices != currentServices.orEmpty()) {
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    migratedServices
                )
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            DebugLogger.d(TAG, "已通过 WRITE_SECURE_SETTINGS 迁移无障碍服务设置。")
            true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "通过 WRITE_SECURE_SETTINGS 迁移无障碍服务设置失败，回退到 Shell。", e)
            false
        }
    }

    /**
     * 获取服务实例，使用 Mutex 解决并发问题 (仅用于 Shizuku)。
     */
    private suspend fun getService(context: Context): IShizukuUserService? {
        // 在尝试加锁前，先进行一次快速检查，提高性能
        if (shellService?.asBinder()?.isBinderAlive == true) {
            return shellService
        }

        // 使用互斥锁确保只有一个协程可以执行连接操作
        connectionMutex.withLock {
            // 获取锁后，再次检查
            if (shellService?.asBinder()?.isBinderAlive == true) {
                return shellService
            }

            // 在锁内执行带重试的连接逻辑
            for (attempt in 1..MAX_RETRY_COUNT) {
                try {
                    shellService = connect(context)
                    // 连接成功后，直接返回
                    return shellService
                } catch (e: Exception) {
                    DebugLogger.w(TAG, "第 $attempt 次连接 Shizuku 服务失败", e)
                    if (attempt < MAX_RETRY_COUNT) {
                        delay(500L * attempt)
                    }
                }
            }
            // 所有重试失败后，返回 null
            return null
        }
    }

    /**
     * 简化 connect 函数，移除并发控制逻辑。
     * 它现在只负责执行单次的绑定尝试。
     */
    private suspend fun connect(context: Context): IShizukuUserService {
        if (!isShizukuActive(context)) {
            throw RuntimeException("Shizuku 未激活或未授权。")
        }

        return withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, ShizukuUserService::class.java))
                    .daemon(false)
                    .processNameSuffix(":vflow_shizuku")
                    .version(1)

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (service != null && service.isBinderAlive && continuation.isActive) {
                            DebugLogger.d(TAG, "Shizuku 服务已连接。")
                            val shell = IShizukuUserService.Stub.asInterface(service)
                            shellService = shell
                            continuation.resume(shell)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        shellService = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(RuntimeException("Shizuku 服务连接意外断开。"))
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        Shizuku.unbindUserService(userServiceArgs, connection, true)
                    } catch (e: Exception) {
                        // 忽略解绑时的异常
                    }
                }

                // 确保绑定操作在主线程执行。
                // 注意：Shizuku API 13.x 的 bindUserService 返回 Unit（无返回值），
                // 无法据其判断绑定是否发起成功，因此依赖上方 BIND_TIMEOUT_MS 作为兜底。
                scope.launch(Dispatchers.Main) {
                    Shizuku.bindUserService(userServiceArgs, connection)
                }
            }
        }
    }
}
