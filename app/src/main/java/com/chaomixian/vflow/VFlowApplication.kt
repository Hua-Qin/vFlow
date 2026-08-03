package com.chaomixian.vflow

import android.app.Application
import com.chaomixian.vflow.services.VFlowAppContextProvider
import com.chaomixian.vflow.services.VFlowShizukuController
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.services.AccessibilityServiceStatus
import com.chaomixian.vflow.core.logging.CrashReportManager
import com.chaomixian.vflow.core.telemetry.TelemetryManager

class VFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 先把 appContext 注入，保证全局单例（ShizukuController 等）可以在没有 context 的
        // 入口（ShellManager.isShizukuActive 内部判断 Shizuku 包是否安装时）取到 packageManager。
        VFlowAppContextProvider.appContext = applicationContext

        LogManager.initialize(applicationContext)
        DebugLogger.initialize(applicationContext)
        CrashReportManager.install(applicationContext)
        TelemetryManager.preInit(applicationContext)
        if (TelemetryManager.isEnabled(applicationContext)) {
            TelemetryManager.init(applicationContext)
        }

        // 全局 Shizuku / Sui 控制器：
        //  - 注册 BinderReceived / BinderDead / PermissionResult 全局监听
        //  - 兜底 Sui.init（ShizukuProvider 虽然会自动 init，但 :vflow_shizuku 等非主进程
        //    有时会绕过 Provider.onCreate 的自动初始化，因此再显式做一次）
        //  - enableMultiProcessSupport
        VFlowShizukuController.initialize(this)

        // 无障碍服务状态：尽早在 Application 启动时做一次检查，用于后续状态检查的缓存
        AccessibilityServiceStatus.isEnabledInSettings(applicationContext)
    }
}
