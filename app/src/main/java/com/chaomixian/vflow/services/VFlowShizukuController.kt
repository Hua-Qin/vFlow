package com.chaomixian.vflow.services

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.locale.toast
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import rikka.sui.Sui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * vFlow 全局 Shizuku/Sui 控制器。
 *
 * 职责（完全对照 Shizuku-API 13.x 官方规范 + v11 迁移指南）：
 *  - 在 Application 级别注册 Shizuku Binder 生命周期监听（BinderReceived / BinderDead），
 *    维护「Shizuku 可用」的单一可信状态（@see ShizukuState）；
 *  - 统一接管权限申请（requestPermission / shouldShowRequestPermissionRationale），
 *    任何模块想用 Shizuku 都走 [ensureReady] 或 [requestPermissionIfNeeded]，不用自己弹 dialog；
 *  - Application 启动时主动兜底 Sui.init（ShizukuProvider 虽然在 12.1+ 会自动 init Sui，
 *    但某些 ROM 的 ContentProvider 初始化顺序会让 :vflow_shizuku 等非主进程绕过 Sui 初始化，
 *    必须再做一次幂等调用）；
 *  - 多进程场景下调用 [ShizukuProvider.enableMultiProcessSupport]（官方要求）；
 *  - 所有 Shizuku#removeXxxListener 统一包 try/catch（官方 13.1.1 changelog 提到 Android 7.1
 *    之前 CopyOnWriteArrayList#removeIf 不支持，会抛 UnsupportedOperationException）。
 *
 * 对外入口：
 *  - [initialize]   只在 VFlowApplication.onCreate 调用一次，多进程安全幂等
 *  - [stateFlow]    只读 StateFlow，UI 可 collect 做即时刷新
 *  - [eventFlow]    SharedFlow 发送一次性事件（权限被用户在 Shizuku App 里撤销等）
 *  - [ensureReady]  suspend，阻塞直到授权完成/失败，返回 [ShizukuReadyResult]
 *  - [requestPermissionIfNeeded]  Activity 中需要用户交互时调用
 *  - [openShizukuAppOrDownload]  缺 Shizuku 时跳转安装/激活
 */
@Keep
object VFlowShizukuController {

    private const val TAG = "VFlowShizukuCtrl"

    /** Shizuku/Sui 的高阶可用状态（UI 可直接展示） */
    enum class ShizukuState {
        /** 手机上完全没装 Shizuku App，也不是 Sui（Magisk） */
        NOT_INSTALLED,
        /** Shizuku 已安装或 Sui 存在，但 pingBinder=false（Shizuku 用户还没激活；
         *  对 Sui 来说通常不会出现，除非 Magisk 模块刚装完没重启） */
        INACTIVE,
        /** pingBinder=true 但 checkSelfPermission() != GRANTED */
        UNAUTHORIZED,
        /** 真正可用：pingBinder=true && PERMISSION_GRANTED */
        AUTHORIZED,
        /** 绑定过程中出现不兼容错误，通常是 pre-v11 Shizuku Server */
        UNSUPPORTED_VERSION
    }

    /** [ensureReady] 返回值 */
    sealed class ShizukuReadyResult {
        object AlreadyReady : ShizukuReadyResult()
        object GrantedAfterRequest : ShizukuReadyResult()
        data class DeniedByUser(val rationaleShown: Boolean) : ShizukuReadyResult()
        object ShizukuNotInstalled : ShizukuReadyResult()
        object ShizukuNotActivated : ShizukuReadyResult()
        object UnsupportedVersion : ShizukuReadyResult()
        object Cancelled : ShizukuReadyResult()
    }

    private const val SHIZUKU_PERMISSION_REQ = 10001
    private const val PERMISSION_WAIT_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var initialized = false
    private val initLock = Any()

    private val _stateFlow = MutableStateFlow(ShizukuState.UNAUTHORIZED)
    val stateFlow: StateFlow<ShizukuState> = _stateFlow.asStateFlow()

    /** 一次性事件（只用于 UI 提示，不用于逻辑判断） */
    sealed class Event {
        data class BinderReceived(val uid: Int) : Event()
        object BinderDead : Event()
        data class PermissionResult(val requestCode: Int, val granted: Boolean) : Event()
    }
    private val _eventFlow = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val eventFlow: SharedFlow<Event> = _eventFlow.asSharedFlow()

    // ------------------------------------------------------------------
    // Listeners（用 val 持有引用，保证 add/remove 的是同一个对象）
    // ------------------------------------------------------------------
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        DebugLogger.i(TAG, "BINDER_RECEIVED: Shizuku/Sui Binder 已交付，uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)}")
        recomputeState()
        scope.launch { runCatching { _eventFlow.emit(Event.BinderReceived(runCatching { Shizuku.getUid() }.getOrDefault(-1))) } }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        DebugLogger.w(TAG, "BINDER_DEAD: Shizuku/Sui Binder 断开（Shizuku 服务被 kill 或设备重启）")
        recomputeState()
        scope.launch { runCatching { _eventFlow.emit(Event.BinderDead) } }
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        DebugLogger.i(TAG, "PERMISSION_RESULT: requestCode=$requestCode granted=$granted grantResult=$grantResult")
        recomputeState()
        scope.launch { runCatching { _eventFlow.emit(Event.PermissionResult(requestCode, granted)) } }
    }

    // ==================================================================
    // 初始化
    // ==================================================================
    fun initialize(app: Application) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            initialized = true

            // 1) Sui 兜底初始化（ShizukuProvider.onCreate 在 12.1+ 已自动做，但：
            //    - 非主进程（:vflow_shizuku）可能在 Provider.onCreate 之前用到 Shizuku 类；
            //    - 某些 ContentProvider 启动顺序异常的 ROM 会跳过自动初始化。
            //    所以这里再显式调用一次。Sui.init 本身幂等，返回 false 只是「没装 Sui」。）
            val suiOk = runCatching { Sui.init(app.packageName) }
                .onFailure { DebugLogger.w(TAG, "Sui.init(${app.packageName}) 抛异常（可以忽略）: ${it.message}") }
                .getOrDefault(false)
            DebugLogger.i(TAG, "Sui.init 完成: installed=$suiOk  (Sui 用户此值为 true，Shizuku 用户为 false 均属正常)")

            // 2) 多进程支持（vFlow 至少有 :vflow_shizuku 进程）
            runCatching { ShizukuProvider.enableMultiProcessSupport(false) }
                .onFailure { DebugLogger.w(TAG, "enableMultiProcessSupport 抛异常（通常在 Shizuku 未激活时发生，可忽略）: ${it.message}") }

            // 3) 注册全局监听器（addXxxListener 重复调用是安全的，Shizuku 内部会去重）
            runCatching {
                Shizuku.addBinderReceivedListener(binderReceivedListener, mainHandler)
                Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
                Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
            }.onFailure { DebugLogger.w(TAG, "注册 Shizuku 全局监听失败: ${it.message}") }

            // 4) 初始状态
            DebugLogger.i(TAG, "ShizukuController 初始化完成: processName=${getProcessName(app)}")
            recomputeState()
        }
    }

    /** 只在测试/极端场景调用，一般不需要 */
    fun destroy() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
        }.onFailure { DebugLogger.w(TAG, "removeBinderReceivedListener 异常: ${it.message}") }
        runCatching {
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }.onFailure { DebugLogger.w(TAG, "removeBinderDeadListener 异常: ${it.message}") }
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }.onFailure { DebugLogger.w(TAG, "removeRequestPermissionResultListener 异常: ${it.message}") }
        scope.cancel("VFlowShizukuController.destroy()")
    }

    // ==================================================================
    // 状态机
    // ==================================================================
    private fun recomputeState() {
        val newState = computeCurrentStateInternal()
        DebugLogger.d(TAG, "状态重算 -> $newState  (binderAlive=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)})")
        _stateFlow.compareAndSet(_stateFlow.value, newState)
    }

    private fun computeCurrentStateInternal(): ShizukuState {
        // Pre-v11 Server 已被官方 12.1.0+ drop 支持。
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return ShizukuState.UNSUPPORTED_VERSION
        }
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!ping) {
            // Binder 都拿不到：NOT_INSTALLED（既没有 Sui，也找不到 Shizuku App 的 Provider）
            // 或 INACTIVE（Shizuku/Sui 在，但用户还没把 Shizuku 激活）
            val hasSuiOrShizuku = runCatching { Sui.isSui() }.getOrDefault(false) || isShizukuAppInstalledInternal()
            return if (hasSuiOrShizuku) ShizukuState.INACTIVE else ShizukuState.NOT_INSTALLED
        }
        val perm = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        return if (perm == PackageManager.PERMISSION_GRANTED) ShizukuState.AUTHORIZED
        else ShizukuState.UNAUTHORIZED
    }

    private fun isShizukuAppInstalledInternal(): Boolean {
        // rikka.shizuku（Shizuku 新版包名）+ moe.shizuku.privileged.api（老版包名）
        return try {
            @Suppress("DEPRECATION")
            val pm = VFlowAppContextProvider.appContext?.packageManager
            pm?.getPackageInfo("rikka.shizuku", 0) != null ||
                runCatching { pm?.getPackageInfo("moe.shizuku.privileged.api", 0) != null }.getOrDefault(false)
        } catch (_: Throwable) {
            false
        }
    }

    // ==================================================================
    // 入口：ensureReady / requestPermissionIfNeeded / openShizukuAppOrDownload
    // ==================================================================

    /**
     * 阻塞式等待 Shizuku 达到 AUTHORIZED。
     *
     * @param autoRequestIfPossible true: 若只是未授权（UNAUTHORIZED），自动调用
     *   Shizuku.requestPermission，并等权限回调；否则直接返回当前状态。
     */
    suspend fun ensureReady(
        context: Context,
        timeoutMs: Long = PERMISSION_WAIT_TIMEOUT_MS,
        autoRequestIfPossible: Boolean = true
    ): ShizukuReadyResult {
        // 1. 先快查
        val cur = computeCurrentStateInternal()
        _stateFlow.compareAndSet(_stateFlow.value, cur)
        return when (cur) {
            ShizukuState.AUTHORIZED -> ShizukuReadyResult.AlreadyReady
            ShizukuState.NOT_INSTALLED -> ShizukuReadyResult.ShizukuNotInstalled
            ShizukuState.UNSUPPORTED_VERSION -> ShizukuReadyResult.UnsupportedVersion
            ShizukuState.INACTIVE -> ShizukuReadyResult.ShizukuNotActivated
            ShizukuState.UNAUTHORIZED -> {
                if (!autoRequestIfPossible) {
                    ShizukuReadyResult.DeniedByUser(
                        rationaleShown = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false))
                } else {
                    // 等一次权限结果；shouldShowRationale=Don't ask again 就不要硬弹
                    val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
                    if (rationale) {
                        DebugLogger.w(TAG, "ensureReady: shouldShowRequestPermissionRationale=true，用户选过 \"拒绝且不再询问\"，不调用 requestPermission，请引导用户手动去 Shizuku App 开权限。")
                        return ShizukuReadyResult.DeniedByUser(rationaleShown = true)
                    }
                    waitForPermissionAfterRequest(timeoutMs)
                }
            }
        }
    }

    private suspend fun waitForPermissionAfterRequest(timeoutMs: Long): ShizukuReadyResult = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != SHIZUKU_PERMISSION_REQ) return
                    runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                    if (cont.isActive) {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            cont.resume(ShizukuReadyResult.GrantedAfterRequest)
                        } else {
                            val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
                            cont.resume(ShizukuReadyResult.DeniedByUser(rationale))
                        }
                    }
                }
            }
            runCatching { Shizuku.addRequestPermissionResultListener(listener, mainHandler) }
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ) }
                .onFailure { t ->
                    DebugLogger.e(TAG, "Shizuku.requestPermission 抛异常", t)
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                    if (cont.isActive) cont.resume(ShizukuReadyResult.Cancelled)
                }
            cont.invokeOnCancellation {
                runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            }
        }
    } ?: ShizukuReadyResult.Cancelled

    /**
     * 非 suspend 入口：只负责触发权限申请（由全局 permissionResultListener 回调到 stateFlow）。
     * 若状态不是 UNAUTHORIZED / rationale=true（拒绝不再询问）/ Shizuku 未激活，会返回 false。
     */
    fun requestPermissionIfNeeded(context: Context): Boolean {
        val cur = computeCurrentStateInternal()
        _stateFlow.compareAndSet(_stateFlow.value, cur)
        return when (cur) {
            ShizukuState.UNAUTHORIZED -> {
                val rationale = runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
                if (rationale) {
                    DebugLogger.w(TAG, "requestPermissionIfNeeded: shouldShowRationale=true（用户点过拒绝不再询问），不再弹权限框。")
                    scope.launch(Dispatchers.Main) { context.toast("请手动在 Shizuku App 中为 vFlow 开启权限") }
                    false
                } else {
                    runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQ) }
                        .onFailure { DebugLogger.w(TAG, "Shizuku.requestPermission fail: ${it.message}") }
                        .isSuccess
                }
            }
            ShizukuState.INACTIVE -> {
                openShizukuAppOrDownload(context)
                false
            }
            ShizukuState.NOT_INSTALLED -> {
                openShizukuAppOrDownload(context)
                false
            }
            ShizukuState.AUTHORIZED -> true
            ShizukuState.UNSUPPORTED_VERSION -> false
        }
    }

    /**
     * 跳转到「激活 Shizuku」的最佳路径：
     *  - 已安装 Shizuku App -> 直接打开 Shizuku App 首页（引导用户点「启动」用 adb / 无线调试）
     *  - 未安装 -> 打开浏览器跳官方下载页
     */
    fun openShizukuAppOrDownload(context: Context) {
        val intent = runCatching {
            @Suppress("DEPRECATION")
            val pm = context.packageManager
            listOf("rikka.shizuku", "moe.shizuku.privileged.api").firstNotNullOfOrNull { pkg ->
                runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            }
        }.getOrNull()
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            DebugLogger.i(TAG, "打开 Shizuku App 以激活 Binder: intent=$intent")
            context.startActivity(intent)
            scope.launch(Dispatchers.Main) {
                context.toast("请在 Shizuku App 中「启动」服务后返回 vFlow 重试")
            }
        } else {
            val url = "https://shizuku.rikka.app/download/"
            DebugLogger.i(TAG, "本机未安装 Shizuku App，打开下载页: $url")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }.onFailure { t ->
                DebugLogger.e(TAG, "打开浏览器失败", t)
                scope.launch(Dispatchers.Main) { context.toast("请前往 $url 安装 Shizuku 或安装 Sui (Magisk)") }
            }
        }
    }

    // ==================================================================
    // Helper
    // ==================================================================
    private fun getProcessName(app: Application): String {
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) app.getProcessName()
            else {
                val pid = android.os.Process.myPid()
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: "unknown"
            }
        }.getOrDefault("unknown")
    }

    /** 兼容 [callbackFlow] 用法，UI 可：flow { val events = callbackEvents(); ... collect ... }
     *  目前留作内部调试入口；对外用 stateFlow + eventFlow 更简单。 */
    fun callbackEvents() = callbackFlow {
        val l1 = Shizuku.OnBinderReceivedListener { trySend(Event.BinderReceived(runCatching { Shizuku.getUid() }.getOrDefault(-1))) }
        val l2 = Shizuku.OnBinderDeadListener { trySend(Event.BinderDead) }
        val l3 = Shizuku.OnRequestPermissionResultListener { code, r ->
            trySend(Event.PermissionResult(code, r == PackageManager.PERMISSION_GRANTED))
        }
        runCatching { Shizuku.addBinderReceivedListener(l1, mainHandler) }
        runCatching { Shizuku.addBinderDeadListener(l2, mainHandler) }
        runCatching { Shizuku.addRequestPermissionResultListener(l3, mainHandler) }
        awaitClose {
            runCatching { Shizuku.removeBinderReceivedListener(l1) }
            runCatching { Shizuku.removeBinderDeadListener(l2) }
            runCatching { Shizuku.removeRequestPermissionResultListener(l3) }
        }
    }
}

/**
 * 最小 Application Context provider：避免在纯 object 里硬依赖 Application。
 * VFlowApplication.onCreate 会主动把自己注入进来。
 */
internal object VFlowAppContextProvider {
    @Volatile var appContext: Context? = null
        internal set
}
