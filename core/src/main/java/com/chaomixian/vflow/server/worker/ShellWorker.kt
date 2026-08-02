// 文件: server/src/main/java/com/chaomixian/vflow/server/worker/ShellWorker.kt
package com.chaomixian.vflow.server.worker

import com.chaomixian.vflow.server.common.Config
import com.chaomixian.vflow.server.common.Workarounds
import com.chaomixian.vflow.server.common.utils.SystemUtils
import com.chaomixian.vflow.server.wrappers.shell.*
import kotlin.system.exitProcess

class ShellWorker(
    useUnixSocket: Boolean = false,
    unixSocketPath: String? = null
) : BaseWorker(
    Config.PORT_WORKER_SHELL,
    "Shell",
    useUnixSocket,
    unixSocketPath
) {

    override fun registerWrappers() {
        // 注册所有 Shell 级别的 ServiceWrappers
        serviceWrappers["clipboard"] = IClipboardWrapper()
        serviceWrappers["input"] = IInputManagerWrapper()
        serviceWrappers["audio"] = IAudioManagerWrapper()
        serviceWrappers["wifi"] = IWifiManagerWrapper()
        serviceWrappers["bluetooth_manager"] = IBluetoothManagerWrapper()
        serviceWrappers["nfc"] = INfcAdapterWrapper()
        serviceWrappers["power"] = IPowerManagerWrapper()
        serviceWrappers["activity"] = IActivityManagerWrapper()
        serviceWrappers["connectivity"] = IConnectivityManagerWrapper()
        serviceWrappers["location"] = ILocationManagerWrapper()
        serviceWrappers["alarm"] = IAlarmManagerWrapper()
        serviceWrappers["activity_task"] = IActivityTaskManagerWrapper()
        simpleWrappers["screenshot"] = IScreenshotWrapper()

        // 注意：system target 由 Master 动态路由，不在 wrappers 中注册
    }

    /**
     * ShellWorker 的启动入口
     * 处理权限降级逻辑
     */
    fun run() {
        // 如果通过 vflow_shell_exec 启动，此时应该已经是 Shell 权限
        // 如果通过 app_process 直接启动（回退模式），则需要降权
        if (SystemUtils.isRoot()) {
            System.err.println("⚠️ ShellWorker started as Root, dropping privileges...")
            val dropped = SystemUtils.dropPrivilegesToShell()
            if (!dropped) {
                // 降权失败（Kernelsu/部分 su 管理器下 setuid/setgid 被拒是常见情况）。
                // 不再硬性退出，而是警告后以 root 身份继续——此时 ShellWorker 能启动并对外服务，
                // 只是部分系统服务调用会因包名/uid 不匹配而拒绝，可在后续调用中单独处理。
                System.err.println("⚠️ dropPrivilegesToShell() failed; continuing as UID ${SystemUtils.getMyUid()}." +
                        " (Some system service calls may be denied, but worker will still run.)")
            }
        } else {
            println("✅ ShellWorker running as Shell (UID: ${SystemUtils.getMyUid()})")
        }

        // 应用 FakeContext 工作区，伪装成 com.android.shell
        // 必须在任何服务连接之前调用
        Workarounds.apply()
        println("✅ FakeContext applied as com.android.shell")

        // 启动 ServerSocket
        super.start()
    }
}