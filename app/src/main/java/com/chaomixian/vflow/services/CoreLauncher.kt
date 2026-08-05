// 文件: main/java/com/chaomixian/vflow/services/CoreLauncher.kt
package com.chaomixian.vflow.services

import android.content.Context
import android.widget.Toast
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.permissions.PermissionManager
import kotlinx.coroutines.delay
import java.io.File

/**
 * vFlow Core 启动器。
 *
 * 职责：
 * - 仅负责启动 vFlowCore 进程
 * - 不负责生命周期管理（由 CoreManager 负责）
 * - 不负责健康检查（由 CoreManager 负责）
 */
object CoreLauncher {
    private const val TAG = "CoreLauncher"
    private const val CORE_CLASS = "com.chaomixian.vflow.server.VFlowCore"

    data class ForceStopResult(
        val success: Boolean,
        val killedPids: List<Int> = emptyList(),
        val error: String? = null
    )

    /**
     * 启动模式
     */
    enum class LaunchMode {
        AUTO,    // 自动根据偏好选择
        SHIZUKU, // 强制使用 Shizuku
        ROOT     // 强制使用 Root
    }

    /**
     * 启动结果
     */
    data class LaunchResult(
        val success: Boolean,
        val mode: LaunchMode,
        val privilegeMode: VFlowCoreBridge.PrivilegeMode = VFlowCoreBridge.PrivilegeMode.NONE,
        val error: String? = null
    )

    /**
     * 启动 vFlowCore 进程。
     *
     * @param context 上下文
     * @param mode 启动模式
     * @param forceRestart 是否强制重启（如果已运行）
     * @return 启动结果
     */
    suspend fun launch(
        context: Context,
        mode: LaunchMode = LaunchMode.AUTO,
        forceRestart: Boolean = false
    ): LaunchResult {
        DebugLogger.i(TAG, "准备启动 vFlowCore (模式: $mode, 强制重启: $forceRestart)...")
        val finalMode = resolveLaunchMode(context, mode)

        // 如果 Core 已经在运行且不是强制重启模式，直接返回
        if (!forceRestart && VFlowCoreBridge.ping()) {
            DebugLogger.d(TAG, "vFlowCore 已在运行，无需启动")
            return LaunchResult(
                success = true,
                mode = finalMode,
                privilegeMode = VFlowCoreBridge.privilegeMode
            )
        }

        if (forceRestart) {
            if (VFlowCoreBridge.ping()) {
                DebugLogger.d(TAG, "强制重启模式：检测到旧的 vFlowCore 正在运行，先停止...")
                // 等待真退出口令端口关闭后再启动，避免 exit → 新 Core 启动后被旧 Core 同进程退出时序搞 ECONNREFUSED
                stop(context, waitForExit = true, timeoutMs = 5000L)
            } else {
                DebugLogger.d(TAG, "强制重启模式：Core 未运行，跳过 stop 直接启动")
            }
        }

        return try {
            // 1. 部署 Dex 到公共目录
            val dexFile = deployDex(context)
            if (dexFile == null) {
                DebugLogger.e(TAG, "部署 vFlowCore.dex 失败")
                return LaunchResult(
                    success = false,
                    mode = mode,
                    error = "部署 vFlowCore.dex 失败"
                )
            }

            // 2. 准备日志文件
            val logFile = File(StorageManager.logsDir, "server_process.log")

            // 3. 构建启动命令
            val command = buildLaunchCommand(dexFile, logFile, finalMode, context)

            // 4. 执行启动命令
            // 当 finalMode 为 SHIZUKU 时，ShellManager 会先试 Shizuku，失败后回退到 Core/Root。
            // 但 Core 还没启动（这正是我们在做的事），所以实际会回退到 Root 执行。
            // Root 执行 SHIZUKU 模式的命令（直接启动，无 vflow_shell_exec 降权）也能工作，
            // 只是 Core 会以 Root 身份运行而非 Shell——对功能无影响。
            // 为进一步加速：如果 Root 可用，直接用 Root 模式执行，跳过 Shizuku 8s 超时。
            val shellMode = if (finalMode == LaunchMode.SHIZUKU && ShellManager.isRootAvailable()) {
                DebugLogger.d(TAG, "Shizuku 模式但 Root 可用，直接用 Root 执行启动命令（跳过 Shizuku 绑定超时）")
                ShellManager.ShellMode.ROOT
            } else {
                finalMode.toShellMode()
            }

            DebugLogger.d(TAG, "执行启动命令: $command (ShellMode: $shellMode)")
            val result = ShellManager.execShellCommand(context, command, shellMode)

            if (result.startsWith("Error")) {
                DebugLogger.e(TAG, "vFlowCore 启动命令执行失败: $result")
                // 如果首次执行失败且当前不是 ROOT 模式，尝试用 ROOT 模式重试
                if (shellMode != ShellManager.ShellMode.ROOT && ShellManager.isRootAvailable()) {
                    DebugLogger.w(TAG, "首次启动失败，尝试用 ROOT 模式重试...")
                    val rootCommand = buildLaunchCommand(dexFile, logFile, LaunchMode.ROOT, context)
                    val rootResult = ShellManager.execShellCommand(context, rootCommand, ShellManager.ShellMode.ROOT)
                    if (rootResult.startsWith("Error")) {
                        DebugLogger.e(TAG, "ROOT 模式重试也失败: $rootResult")
                        return LaunchResult(success = false, mode = LaunchMode.ROOT, error = rootResult)
                    }
                    DebugLogger.i(TAG, "ROOT 模式重试成功，等待响应...")
                } else {
                    return LaunchResult(success = false, mode = finalMode, error = result)
                }
            } else {
                DebugLogger.i(TAG, "vFlowCore 启动命令已发送，正在等待响应...")
                // 给一点时间让进程启动
                delay(500)

                // 验证启动（带重试机制，最多等待5秒）
                var success = false
                for (i in 1..10) {
                    if (VFlowCoreBridge.ping()) {
                        DebugLogger.i(TAG, "vFlowCore 启动验证成功！(尝试 $i/10) 权限: ${VFlowCoreBridge.privilegeMode}")
                        success = true
                        break
                    }
                    if (i < 10) {
                        DebugLogger.d(TAG, "vFlowCore 未响应，继续等待... ($i/10)")
                        delay(500) // 每次等待500ms，总共最多5秒
                    }
                }

                if (success) {
                    LaunchResult(
                        success = true,
                        mode = finalMode,
                        privilegeMode = VFlowCoreBridge.privilegeMode
                    )
                } else {
                    // 启动后未响应：立即读 server_process.log 的头部和尾部（如果存在），
                    // 写入 App 调试日志，便于在不 adb 的情况下定位（setuid 失败/类加载错误等）。
                    val headTail = try {
                        if (logFile.exists() && logFile.length() > 0L) {
                            val head = logFile.readBytes().take(2048).toByteArray().toString(Charsets.UTF_8)
                            val tail = if (logFile.length() > 2048) {
                                val start = (logFile.length() - 2048).coerceAtLeast(0L)
                                val arr = java.io.RandomAccessFile(logFile, "r").use { raf ->
                                    raf.seek(start)
                                    val buf = ByteArray(2048)
                                    val n = raf.read(buf)
                                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                                }
                                String(arr, Charsets.UTF_8)
                            } else ""
                            "（${logFile.length()} bytes）\n===== HEAD =====\n$head\n${if (tail.isNotEmpty()) "===== TAIL =====\n$tail" else ""}"
                        } else if (logFile.exists()) {
                            "（文件为空，${logFile.length()} bytes）"
                        } else {
                            "（日志文件不存在）"
                        }
                    } catch (t: Throwable) {
                        "（读取日志异常: ${t.javaClass.simpleName}: ${t.message}）"
                    }
                    DebugLogger.w(TAG, "vFlowCore 启动后未响应 Ping (尝试10次后仍失败)。\n" +
                            "server_process.log 路径: ${logFile.absolutePath}\n" +
                            "server_process.log 内容: $headTail")
                    LaunchResult(
                        success = false,
                        mode = finalMode,
                        error = "启动后未响应 Ping (等待5秒后超时). server_process.log: $headTail"
                    )
                }
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "启动过程发生异常", e)
            LaunchResult(
                success = false,
                mode = finalMode,
                error = e.message
            )
        }
    }

    /**
     * 停止 vFlowCore 进程。
     * 发送 shutdown 请求让 Core 自己退出，不使用 pkill。
     */
    suspend fun stop(context: Context): Boolean {
        return stop(context, waitForExit = true, timeoutMs = 5000L)
    }

    /**
     * 停止 vFlowCore。
     *
     * @param waitForExit true 时会等待「ping 不再响应 + PID 消失」两者同时满足，避免 exit 指令
     * 在 shutdown hook 与新 Core 启动之间产生时序竞争（旧 Core 把刚 accept 到的新请求按 exit
     * 流程结束时顺便退出，导致新启动 Core 还没 bind 上来就 ECONNREFUSED）。
     */
    suspend fun stop(context: Context, waitForExit: Boolean, timeoutMs: Long = 5000L): Boolean {
        DebugLogger.i(TAG, "正在停止 vFlowCore...")

        // 0. 尝试先记一下当前 Core 的 PID，便于后面判断「真的是这个旧进程退出了」
        val oldPidsBefore = try {
            ShellManager.execShellCommand(context, buildFindCorePidCommand(), ShellManager.ShellMode.AUTO)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                .toSet()
        } catch (_: Exception) { emptySet() }
        if (oldPidsBefore.isNotEmpty()) {
            DebugLogger.d(TAG, "停止前观测到 vFlowCore PID=${oldPidsBefore.joinToString()}")
        }

        // 1. 发送优雅退出请求
        val shutdownSent = VFlowCoreBridge.shutdown()

        if (!shutdownSent) {
            DebugLogger.w(TAG, "发送 shutdown 请求失败，可能 Core 已停止")
        } else {
            DebugLogger.i(TAG, "shutdown 请求已成功发送")
        }

        // 2. 主动断开 Bridge 连接（避免 ping() 重连
        VFlowCoreBridge.disconnect()

        // 3. 等待 Core 进程真正退出（端口关闭 + PID 消失）
        if (!waitForExit) {
            DebugLogger.i(TAG, "vFlowCore 停止请求已完成（未等待退出）")
            return true
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var iteration = 0
        val shellMode = resolveLaunchMode(context, LaunchMode.AUTO).toShellMode()
        while (System.currentTimeMillis() < deadline) {
            iteration++
            val pingAlive = try {
                val bridgeConnected = VFlowCoreBridge.ping()
                bridgeConnected
            } catch (_: Exception) { false }

            val findCmd = buildFindCorePidCommand()
            val pidsNow = try {
                ShellManager.execShellCommand(context, findCmd, shellMode)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.all(Char::isDigit) }
                    .toSet()
            } catch (_: Exception) { emptySet() }
            val anyOldPidStillAlive = oldPidsBefore.any { it in pidsNow }

            if (!pingAlive && !anyOldPidStillAlive) {
                DebugLogger.i(TAG, "vFlowCore 已确认退出 (iter=$iteration)")
                return true
            }
            DebugLogger.d(
                TAG,
                "等待退出 iter=$iteration: pingAlive=$pingAlive, " +
                    "remainingOldPids=${(oldPidsBefore intersect pidsNow).joinToString()}, " +
                    "allNowPids=${pidsNow.joinToString()}"
            )
            delay(200)
        }

        // 超时了还在：尝试强制 kill（用 stop()
        DebugLogger.w(TAG, "等待 vFlowCore 退出超时(${timeoutMs}ms)，尝试强制清理残留进程...")
        forceStop(context)
        delay(300)
        DebugLogger.i(TAG, "vFlowCore 停止请求已完成（超时兜底）")
        return true
    }

    /**
     * 重启 vFlowCore 进程。
     */
    suspend fun restart(context: Context, mode: LaunchMode = LaunchMode.AUTO): LaunchResult {
        DebugLogger.i(TAG, "正在重启 vFlowCore...")
        stop(context)
        delay(500)
        return launch(context, mode, forceRestart = true)
    }

    /**
     * 强制结束残留的 vFlowCore 进程。
     * 通过 shell 搜索包含 Core 主类名的 app_process 命令行，并执行 kill -9。
     */
    suspend fun forceStop(context: Context, mode: LaunchMode = LaunchMode.AUTO): ForceStopResult {
        val finalMode = resolveLaunchMode(context, mode)
        val shellMode = finalMode.toShellMode()
        val findCommand = buildFindCorePidCommand()

        return try {
            val findResult = ShellManager.execShellCommandWithResult(context, findCommand, shellMode)
            if (!findResult.success) {
                DebugLogger.e(TAG, "搜索 vFlowCore 进程失败: ${findResult.output}")
                return ForceStopResult(success = false, error = findResult.output)
            }

            val pids = findResult.output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }
                .distinct()
                .toList()

            if (pids.isEmpty()) {
                VFlowCoreBridge.disconnect()
                DebugLogger.i(TAG, "未找到存活的 vFlowCore 进程")
                return ForceStopResult(success = true)
            }

            val killCommand = "kill -9 ${pids.joinToString(" ")}"
            val killResult = ShellManager.execShellCommandWithResult(context, killCommand, shellMode)
            if (!killResult.success) {
                DebugLogger.e(TAG, "强制结束 vFlowCore 进程失败: ${killResult.output}")
                return ForceStopResult(success = false, killedPids = pids, error = killResult.output)
            }

            VFlowCoreBridge.disconnect()
            DebugLogger.w(TAG, "已强制结束 vFlowCore 进程: ${pids.joinToString(", ")}")
            ForceStopResult(success = true, killedPids = pids)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "强制结束 vFlowCore 进程时发生异常", e)
            ForceStopResult(success = false, error = e.message)
        }
    }

    private fun resolveLaunchMode(context: Context, mode: LaunchMode): LaunchMode {
        if (mode != LaunchMode.AUTO) {
            return mode
        }
        val prefs = context.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        val preferredMode = prefs.getString("preferred_core_launch_mode", "shizuku")
        return if (preferredMode == "root") LaunchMode.ROOT else LaunchMode.SHIZUKU
    }

    private fun LaunchMode.toShellMode(): ShellManager.ShellMode {
        return when (this) {
            LaunchMode.ROOT -> ShellManager.ShellMode.ROOT
            LaunchMode.SHIZUKU -> ShellManager.ShellMode.SHIZUKU
            LaunchMode.AUTO -> ShellManager.ShellMode.AUTO
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

    private fun buildFindCorePidCommand(): String {
        val classPattern = shellQuote(CORE_CLASS)
        return "ps -A -o PID=,ARGS= | grep -F $classPattern | grep app_process | grep -v grep | awk '{print \$1}'"
    }

    /**
     * 部署 vFlowCore.dex 到公共目录。
     */
    private fun deployDex(context: Context): File? {
        // 检查存储权限
        if (!PermissionManager.isGranted(context, PermissionManager.STORAGE)) {
            DebugLogger.w(TAG, "存储权限未授予，无法部署 vFlowCore.dex")
            Toast.makeText(
                context,
                "需要文件访问权限才能启动 vFlowCore",
                Toast.LENGTH_LONG
            ).show()
            return null
        }

        val dexFile = File(StorageManager.tempDir, "vFlowCore.dex")

        try {
            // StorageManager.tempDir 已经会创建目录，不需要额外检查
            context.assets.open("vFlowCore.dex").use { input ->
                dexFile.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    DebugLogger.d(TAG, "Dex 已部署到公共目录: ${dexFile.absolutePath} ($bytesCopied bytes)")

                    // 验证文件大小
                    if (dexFile.length() == 0L) {
                        DebugLogger.e(TAG, "部署的文件大小为0")
                        return null
                    }
                }
            }

            // 设置文件可读
            dexFile.setReadable(true, false)

            return dexFile
        } catch (e: Exception) {
            DebugLogger.e(TAG, "部署 vFlowCore.dex 失败", e)
            Toast.makeText(
                context,
                "部署 vFlowCore.dex 失败: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            return null
        }
    }

    /**
     * 构建启动命令。
     */
    private fun buildLaunchCommand(
        dexFile: File,
        logFile: File,
        mode: LaunchMode,
        context: Context
    ): String {
        val classpath = dexFile.absolutePath
        val logPath = logFile.absolutePath
        val packageName = context.packageName
        val transportArgs = buildTransportArgs(context)

        return if (mode == LaunchMode.ROOT) {
            // ROOT 模式：需要部署 vflow_shell_exec 来降权启动 shell worker
            val shellLauncher = deployShellLauncher(context)
            if (shellLauncher != null) {
                // 注意：脚本必须用 trap EXIT 自删除，不能与启动命令在同一行用 & 并列——
                // 否则某些 shell 实现在 app_process 读完脚本前就 rm 掉脚本自身，导致
                // "sh: can't open ...: No such file" 或脚本内容截断。
                val tempScript = File(StorageManager.tempDir, "start_vflowcore_${System.currentTimeMillis()}.sh")
                tempScript.writeText("""
                    #!/system/bin/sh
                    # 退出时再删自己，保证读完。
                    trap 'rm -f "\$0"' EXIT
                    export CLASSPATH="$classpath"
                    exec app_process /system/bin $CORE_CLASS --shell-launcher "${shellLauncher.absolutePath}" --app-package "$packageName" $transportArgs
                """.trimIndent())
                tempScript.setExecutable(true)
                tempScript.setReadable(true, false)

                DebugLogger.d(TAG, "ROOT 模式：使用 vflow_shell_exec 降权，path: ${shellLauncher.absolutePath}")
                "sh ${tempScript.absolutePath} > \"$logPath\" 2>&1 &"
            } else {
                DebugLogger.w(TAG, "ROOT 模式但 vflow_shell_exec 部署失败，回退到直接启动（可能有权限问题）")
                "sh -c 'export CLASSPATH=\"$classpath\"; exec app_process /system/bin $CORE_CLASS --app-package \"$packageName\" $transportArgs' > \"$logPath\" 2>&1 &"
            }
        } else {
            // Shizuku 或 AUTO 模式：Master 以 shell 权限运行，无需降权，直接启动
            DebugLogger.d(TAG, "Shell 模式：直接启动（无需降权）")
            "sh -c 'export CLASSPATH=\"$classpath\"; exec app_process /system/bin $CORE_CLASS --app-package \"$packageName\" $transportArgs' > \"$logPath\" 2>&1 &"
        }
    }

    private fun buildTransportArgs(context: Context): String {
        return if (VFlowCoreBridge.isUnixSocketEnabled(context)) {
            val socketName = VFlowCoreBridge.getUnixSocketName(context)
            DebugLogger.d(TAG, "使用 UNIX 套接字: @$socketName")
            "--ipc-transport unix --unix-socket-name \"$socketName\""
        } else {
            "--ipc-transport tcp"
        }
    }

    /**
     * 部署 vflow_shell_exec 到应用私有目录。
     */
    private fun deployShellLauncher(context: Context): File? {
        val deviceAbi = detectDeviceAbi()
        DebugLogger.d(TAG, "Device ABI: $deviceAbi")

        val execDir = File(context.filesDir, "bin")
        if (!execDir.exists()) {
            execDir.mkdirs()
        }

        val execFile = File(execDir, "vflow_shell_exec")
        val assetPath = "vflow_shell_exec/$deviceAbi/vflow_shell_exec"

        return try {
            context.assets.open(assetPath).use { input ->
                execFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // 设置可执行权限 (755: rwxr-xr-x)
            execFile.setExecutable(true, false)
            execFile.setReadable(true, false)
            execFile.setWritable(true, true) // 仅所有者可写

            DebugLogger.d(TAG, "vflow_shell_exec deployed: ${execFile.absolutePath}")
            execFile
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to deploy vflow_shell_exec", e)
            null
        }
    }

    /**
     * 检测设备架构。
     */
    private fun detectDeviceAbi(): String {
        val primaryAbi = android.os.Build.SUPPORTED_ABIS[0]
        return when {
            primaryAbi.startsWith("arm64") -> "arm64-v8a"
            primaryAbi.startsWith("armeabi-v7a") -> "armeabi-v7a"
            primaryAbi.startsWith("x86_64") -> "x86_64"
            primaryAbi.startsWith("x86") -> "x86"
            else -> "arm64-v8a" // 默认
        }
    }
}
