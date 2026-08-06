// 文件: server/src/main/java/com/chaomixian/vflow/server/VFlowCore.kt
package com.chaomixian.vflow.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.chaomixian.vflow.server.common.CoreBuildInfo
import com.chaomixian.vflow.server.common.Config
import com.chaomixian.vflow.server.common.utils.SystemUtils
import com.chaomixian.vflow.server.worker.RootWorker
import com.chaomixian.vflow.server.worker.ShellWorker
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * vFlow Core 入口
 */
object VFlowCore {
    private enum class MasterTransport {
        TCP,
        UNIX
    }

    private var isRunning = true
    private val executor = Executors.newCachedThreadPool()
    private val workerProcesses = mutableListOf<Process>()
    private var shellLauncherPath: String? = null
    private var appPackageName: String? = null
    private var appWatcherJob: Thread? = null
    private var masterTransport: MasterTransport = MasterTransport.TCP
    private var unixSocketName: String? = null

    @JvmStatic
    fun main(args: Array<String>) {
        var isWorker = false
        var workerType = ""

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--worker" -> isWorker = true
                "--type" -> {
                    if (i + 1 < args.size) workerType = args[i + 1]
                    i++
                }
                "--shell-launcher" -> {
                    if (i + 1 < args.size) {
                        shellLauncherPath = args[i + 1]
                        i++
                    }
                }
                "--app-package" -> {
                    if (i + 1 < args.size) {
                        appPackageName = args[i + 1]
                        i++
                    }
                }
                "--ipc-transport" -> {
                    if (i + 1 < args.size) {
                        masterTransport = if (args[i + 1].equals("unix", ignoreCase = true)) {
                            MasterTransport.UNIX
                        } else {
                            MasterTransport.TCP
                        }
                        i++
                    }
                }
                "--unix-socket-name" -> {
                    if (i + 1 < args.size) {
                        unixSocketName = args[i + 1]
                        i++
                    }
                }
            }
            i++
        }

        if (isWorker) {
            runAsWorker(workerType)
        } else {
            runAsMaster()
        }
    }

    // ================= Worker 逻辑 =================

    private fun runAsWorker(type: String) {
        // 全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            System.err.println("❌ Uncaught Exception in Worker [$type]: ${e.message}")
            e.printStackTrace()
        }

        // Worker 始终使用 UNIX 抽象 socket（与 masterTransport 解耦）。
        // 原因见 buildWorkerTransportArgs 的注释：ShellWorker 降权后若仍在 untrusted_app 域，
        // 绑定 TCP ServerSocket 会被 SELinux 拒绝（EACCES）。UNIX 抽象 socket 任何 uid/域都能 bind。
        val useUnixSocket = true
        val resolvedUnixSocketName = unixSocketName ?: Config.getWorkerSocketName(resolveWorkerType(type), appPackageName)

        when (type) {
            "shell" -> ShellWorker(useUnixSocket, resolvedUnixSocketName).run() // 逻辑已移入 ShellWorker
            "root" -> RootWorker(useUnixSocket, resolvedUnixSocketName).run()
            else -> {
                System.err.println("Unknown worker type: $type")
                exitProcess(1)
            }
        }
    }

    // ================= Master 逻辑 =================

    private fun runAsMaster() {
        val isRoot = SystemUtils.isRoot()
        println(">>> vFlow Core MASTER Starting (PID: ${android.os.Process.myPid()}, UID: ${SystemUtils.getMyUid()}) <<<")
        println(">>> Core Version: ${CoreBuildInfo.VERSION_CODE} <<<")
        if (masterTransport == MasterTransport.UNIX) {
            println(">>> IPC Transport: UNIX (@${resolveUnixSocketName()}) <<<")
        } else {
            println(">>> IPC Transport: TCP (${Config.BIND_ADDRESS}:${Config.PORT_MASTER}) <<<")
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            workerProcesses.forEach { SystemUtils.killProcess(it) }
            appWatcherJob?.interrupt()
        })

        if (masterTransport == MasterTransport.UNIX) {
            runUnixMasterServer(isRoot)
        } else {
            runTcpMasterServer(isRoot)
        }
    }

    private fun spawnWorkers(isRoot: Boolean, shellLauncherPath: String?) {
        println("--- Spawning Workers ---")

        // 1. 启动 Shell Worker
        try {
            val shellWorkerArgs = buildWorkerTransportArgs(Config.WorkerType.SHELL)
            var shellProcess: Process? = null
            var usedShellLauncher = false
            if (shellLauncherPath != null) {
                // 优先用 vflow_shell_exec（降权 + SELinux 切换），但如果它因为 setuid 失败
                // 或其他原因瞬时退出（< 1500ms），立即回退到直接启动 + Kotlin 层 dropPrivilegesToShell()。
                try {
                    val p = SystemUtils.startWorkerProcess("shell", shellLauncherPath, shellWorkerArgs)
                    usedShellLauncher = true
                    // waitFor(timeout)=true 表示进程在 1500ms 内已退出，说明启动瞬时失败。
                    val exitedFast = try {
                        p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) { false }
                    if (exitedFast) {
                        val exitCode = try { p.exitValue() } catch (_: IllegalThreadStateException) { -65536 }
                        System.err.println("⚠️ ShellWorker via vflow_shell_exec exited almost immediately (exit=$exitCode); " +
                                "falling back to direct launch with dropPrivilegesToShell().")
                        try { p.destroy() } catch (_: Exception) {}
                        // 回退：直接启动 app_process，让 ShellWorker 内部 dropPrivilegesToShell() 执行降权。
                        shellProcess = SystemUtils.startWorkerProcess("shell", shellWorkerArgs)
                    } else {
                        shellProcess = p
                    }
                } catch (e: Exception) {
                    System.err.println("⚠️ Failed to start ShellWorker via vflow_shell_exec (${e.message}); " +
                            "falling back to direct launch with dropPrivilegesToShell().")
                    shellProcess = SystemUtils.startWorkerProcess("shell", shellWorkerArgs)
                }
            } else {
                shellProcess = SystemUtils.startWorkerProcess("shell", shellWorkerArgs)
            }
            workerProcesses.add(shellProcess)
            setupWorkerLogger(shellProcess, if (usedShellLauncher) "ShellWorker" else "ShellWorker(direct)")
        } catch (e: Exception) {
            System.err.println("❌ Failed to start ShellWorker: ${e.message}")
            e.printStackTrace()
        }

        // 2. 启动 Root Worker (仅 Master 为 Root 时，保持原样，不需要 vflow_shell_exec)
        if (isRoot) {
            try {
                val p = SystemUtils.startWorkerProcess("root", buildWorkerTransportArgs(Config.WorkerType.ROOT))
                workerProcesses.add(p)
                setupWorkerLogger(p, "RootWorker")
            } catch (e: Exception) {
                System.err.println("❌ Failed to start RootWorker: ${e.message}")
            }
        }

        // NOTE: 原来这里有 Thread.sleep(1000)，会阻塞 Master accept()，导致
        // 「连接立即成功但 ping 回复要 1~3s」的现象；改为异步等一下，让 worker
        // 有时间 bind 端口，但 Master 自己立刻进入 accept() 保证 ping 低延迟。
        // 我们不会在这里 join，因为 worker 失败是各自日志诊断的问题，不是 Master 启动的阻塞点。
        executor.submit {
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            println("--- Workers spawned (async settle complete) ---")
        }
    }

    private fun setupWorkerLogger(process: Process, tag: String) {
        executor.submit {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { println("[$tag] $it") }
            } catch (ignored: Exception) {}
        }
        executor.submit {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).forEachLine { System.err.println("[$tag ERR] $it") }
            } catch (ignored: Exception) {}
        }
    }

    private fun runTcpMasterServer(isRoot: Boolean) {
        try {
            val serverSocket = ServerSocket(Config.PORT_MASTER, 50, InetAddress.getByName(Config.BIND_ADDRESS))
            serverSocket.reuseAddress = true
            println("✅ Master listening on ${Config.BIND_ADDRESS}:${Config.PORT_MASTER}")

            serverSocket.use { server ->
                spawnWorkers(isRoot, shellLauncherPath)
                startAppWatcher() // 启动 App 进程监控

                while (isRunning) {
                    val client = server.accept()
                    executor.submit { handleMasterTcpClient(client) }
                }
            }
        } catch (e: Exception) {
            System.err.println("❌ Master Fatal: ${e.message}")
            exitProcess(1)
        }
    }

    private fun runUnixMasterServer(isRoot: Boolean) {
        val socketName = resolveUnixSocketName()
        try {
            LocalServerSocket(socketName).use { server ->
                println("✅ Master listening on unix:@$socketName")

                spawnWorkers(isRoot, shellLauncherPath)
                startAppWatcher() // 启动 App 进程监控

                while (isRunning) {
                    val client = server.accept()
                    executor.submit { handleMasterLocalClient(client) }
                }
            }
        } catch (e: Exception) {
            System.err.println("❌ Master Fatal (UNIX): ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    private fun handleMasterTcpClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = Config.SOCKET_TIMEOUT
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val writer = PrintWriter(OutputStreamWriter(s.getOutputStream()), true)
                handleMasterClientLoop(reader, writer)
            } catch (e: Exception) {}
        }
    }

    private fun handleMasterLocalClient(socket: LocalSocket) {
        socket.use { s ->
            try {
                s.soTimeout = Config.SOCKET_TIMEOUT
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                val writer = PrintWriter(OutputStreamWriter(s.outputStream), true)
                handleMasterClientLoop(reader, writer)
            } catch (e: Exception) {
            }
        }
    }

    private fun handleMasterClientLoop(
        reader: BufferedReader,
        writer: PrintWriter
    ) {
        while (isRunning) {
            val reqStr = reader.readLine() ?: break
            val req = try { JSONObject(reqStr) } catch(e:Exception) { null }

            if (req != null) {
                if (req.optString("target") == "system" && req.optString("method") == "exit") {
                    writer.println(JSONObject().put("success", true).toString())
                    isRunning = false
                    executor.submit { Thread.sleep(500); exitProcess(0) }
                    return
                }
                if (tryRouteStreamRequest(req, reqStr, writer)) {
                    return
                }
                writer.println(routeRequest(req.optString("target"), reqStr))
            }
        }
    }

    private fun resolveUnixSocketName(): String {
        val providedPath = unixSocketName?.takeIf { it.isNotBlank() }
        if (providedPath != null) {
            return providedPath
        }
        val packageSuffix = (appPackageName ?: "com.chaomixian.vflow").replace('.', '_')
        return "${packageSuffix}_vflow_core"
    }

    private fun buildWorkerTransportArgs(type: Config.WorkerType): List<String> {
        // Worker 始终使用 UNIX 抽象命名空间 socket 与 Master 通信，与 App↔Master 的传输方式解耦。
        // 原因：ShellWorker 降权到 uid=2000 后，若仍在 untrusted_app SELinux 域内
        // （vflow_shell_exec 失败回退到直接 app_process 启动的场景），绑定 TCP ServerSocket
        // 会被 SELinux 拒绝（EACCES）。UNIX 抽象 socket 不走 inet，任何 uid/域都能 bind。
        return listOf(
            "--ipc-transport",
            "unix",
            "--unix-socket-name",
            Config.getWorkerSocketName(type, appPackageName),
            "--app-package",
            appPackageName.orEmpty()
        )
    }

    private fun resolveWorkerType(type: String): Config.WorkerType {
        return when (type) {
            "root" -> Config.WorkerType.ROOT
            else -> Config.WorkerType.SHELL
        }
    }

    private fun routeRequestToWorker(workerType: Config.WorkerType, requestStr: String): String {
        // Master↔Worker 始终走 UNIX 抽象 socket（见 buildWorkerTransportArgs 的注释）。
        return try {
            val socketName = Config.getWorkerSocketName(workerType, appPackageName)
            LocalSocket(LocalSocket.SOCKET_STREAM).use { ws ->
                ws.connect(
                    LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                )
                ws.soTimeout = Config.SOCKET_TIMEOUT
                val writer = PrintWriter(OutputStreamWriter(ws.outputStream), true)
                val reader = BufferedReader(InputStreamReader(ws.inputStream))
                writer.println(requestStr)
                reader.readLine() ?: JSONObject().put("success", false).put("error", "Empty response").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error", "Worker error: ${e.message}").toString()
        }
    }

    private fun tryRouteStreamRequest(req: JSONObject, requestStr: String, clientWriter: PrintWriter): Boolean {
        val target = req.optString("target")
        val method = req.optString("method")
        if (target != "clipboard" || method != "subscribeClipboardStream") {
            return false
        }

        val workerType = Config.ROUTING_TABLE[target] ?: return false
        relayStreamRequestToWorker(workerType, requestStr, clientWriter)
        return true
    }

    private fun relayStreamRequestToWorker(
        workerType: Config.WorkerType,
        requestStr: String,
        clientWriter: PrintWriter
    ) {
        // Master↔Worker 始终走 UNIX 抽象 socket（见 buildWorkerTransportArgs 的注释）。
        try {
            val socketName = Config.getWorkerSocketName(workerType, appPackageName)
            LocalSocket(LocalSocket.SOCKET_STREAM).use { workerSocket ->
                workerSocket.connect(
                    LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                )
                workerSocket.soTimeout = Config.SOCKET_TIMEOUT
                val workerWriter = PrintWriter(OutputStreamWriter(workerSocket.outputStream), true)
                val workerReader = BufferedReader(InputStreamReader(workerSocket.inputStream))
                workerWriter.println(requestStr)
                if (workerWriter.checkError()) {
                    clientWriter.println(JSONObject().put("success", false).put("error", "Failed to write stream request").toString())
                    return
                }
                while (isRunning) {
                    val line = workerReader.readLine() ?: break
                    clientWriter.println(line)
                    if (clientWriter.checkError()) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            clientWriter.println(
                JSONObject()
                    .put("success", false)
                    .put("error", "Worker stream error: ${e.message}")
                    .toString()
            )
        }
    }

    private fun routeRequest(target: String, requestStr: String): String {
        // 处理 system target 的特殊路由
        if (target == "system") {
            return try {
                val req = JSONObject(requestStr)
                val method = req.optString("method")

                // ping 请求直接返回
                if (method == "ping") {
                    return JSONObject()
                        .put("success", true)
                        .put("uid", SystemUtils.getMyUid())
                        .put("versionCode", CoreBuildInfo.VERSION_CODE)
                        .put("versionName", CoreBuildInfo.VERSION_NAME)
                        .toString()
                }

                // exec 请求根据 asRoot 参数路由
                if (method == "exec") {
                    val asRoot = req.optJSONObject("params")?.optBoolean("asRoot", false) ?: false
                    val workerType = if (asRoot) Config.WorkerType.ROOT else Config.WorkerType.SHELL

                    // 检查目标 Worker 是否存在
                    if (asRoot && !SystemUtils.isRoot()) {
                        return JSONObject().put("success", false).put("error", "RootWorker not available (Master not Root)").toString()
                    }

                    return routeRequestToWorker(workerType, requestStr)
                }

                // exit 请求（系统控制）
                if (method == "exit") {
                    return JSONObject().put("success", true).toString()
                }

                // 其他 system 请求
                JSONObject().put("success", false).put("error", "Unknown system method").toString()
            } catch (e: Exception) {
                JSONObject().put("success", false).put("error", "Invalid request").toString()
            }
        }

        // 其他 target 使用静态路由表
        val workerType = Config.ROUTING_TABLE[target]
        if (workerType == null) {
            return JSONObject().put("success", false).put("error", "No route").toString()
        }

        return routeRequestToWorker(workerType, requestStr)
    }

    // ================= App 进程守护逻辑 =================

    /**
     * 启动 App 进程监控器
     * 定期检查 App 进程是否存活，如果被杀则自动重启
     */
    private fun startAppWatcher() {
        val packageName = appPackageName
        if (packageName == null) {
            println("⚠️ App package name not provided, skipping App watcher")
            return
        }

        println(">>> App Watcher: Starting to monitor app package: $packageName <<<")

        appWatcherJob = Thread {
            var restartAttempts = 0
            val maxRestartAttempts = 10
            val checkInterval = 30_000L // 30秒检查一次

            while (isRunning && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(checkInterval)
                } catch (e: InterruptedException) {
                    break
                }

                // 检查 App 进程是否存活
                val isAppAlive = isAppProcessAlive(packageName)

                if (!isAppAlive) {
                    println("⚠️ App Watcher: App process not found, attempting to restart...")

                    if (restartAttempts < maxRestartAttempts) {
                        restartAttempts++
                        val success = restartApp(packageName)
                        if (success) {
                            println("✅ App Watcher: Successfully restarted App service ($restartAttempts/$maxRestartAttempts)")
                        } else {
                            println("❌ App Watcher: Failed to restart App service ($restartAttempts/$maxRestartAttempts)")
                        }
                    } else {
                        println("⚠️ App Watcher: Max restart attempts reached, giving up")
                        break
                    }
                } else {
                    // App 存活，重置计数器
                    restartAttempts = 0
                }
            }
        }.apply { start() }
    }

    /**
     * 检查 App 进程是否存活
     */
    private fun isAppProcessAlive(packageName: String): Boolean {
        return try {
            // 使用 pidof 检查进程
            val process = ProcessBuilder("sh", "-c", "pidof -s $packageName").start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()

            // 如果有输出说明进程存在
            output.isNotEmpty()

        } catch (e: Exception) {
            // 如果 pidof 不可用，使用 ps 命令
            try {
                val process = ProcessBuilder("sh", "-c", "ps | $packageName").start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output.contains(packageName)
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 重启 App 服务
     *
     * 注意：从 Shell/Root 进程直接 `am start-service` 在 Android 8+ 会被后台服务限制拦截，
     * 报 "app is in background uid null"。这里改用三段式策略：
     *  1. 先 `am start` 拉起 MainActivity（把 App 进程从无到有，并进入前台）；
     *  2. 再 `am start-foreground-service` 启动 TriggerService（前台服务不受后台限制）；
     *  3. 仍失败则只保留 1（App 进程已起来，App 内部可自行拉服务）。
     * 同时附带 --user 0 避免 MIUI 多用户场景下找不到包。
     */
    private fun restartApp(packageName: String): Boolean {
        val mainActivity = "$packageName.ui.main.MainActivity"
        val serviceComponent = "$packageName/${packageName}.services.TriggerService"

        // 步骤 1：拉起 MainActivity 让 App 进程进入前台
        val startActivityCmd = "am start --user 0 -n $packageName/$mainActivity"
        println(">>> App Watcher: Executing: $startActivityCmd <<<")
        var step1Ok = execAmCommand(startActivityCmd)

        // 步骤 2：尝试前台服务方式启动 TriggerService
        val startFgServiceCmd = "am start-foreground-service --user 0 -n $serviceComponent"
        println(">>> App Watcher: Executing: $startFgServiceCmd <<<")
        val step2Ok = execAmCommand(startFgServiceCmd)

        // 步骤 3：如果前台服务失败，尝试普通 start-service（部分 ROM/旧版本仍允许）
        if (!step2Ok) {
            val startServiceCmd = "am start-service --user 0 -n $serviceComponent"
            println(">>> App Watcher: Executing: $startServiceCmd <<<")
            execAmCommand(startServiceCmd)
        }

        // 只要 App 进程被拉起（步骤 1 成功），就认为重启成功——
        // App 的 Application/MainActivity 内部会自行拉起 TriggerService。
        return step1Ok
    }

    private fun execAmCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val exitCode = process.waitFor()
            val error = process.errorStream.bufferedReader().use { it.readText().trim() }
            if (error.isNotEmpty()) {
                System.err.println("App Watcher Error: $error")
            }
            exitCode == 0
        } catch (e: Exception) {
            System.err.println("❌ App Watcher: command failed [$command]: ${e.message}")
            false
        }
    }
}
