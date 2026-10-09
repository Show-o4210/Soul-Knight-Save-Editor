package com.example.soul_knight_save_editor.unlock

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import kotlinx.serialization.json.*
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Future

/** Explicit Shizuku Root backend. It never opens su or silently changes the selected backend. */
class ShizukuSaveAccess(context: Context, private val onStateChanged: () -> Unit = {}) : BaseSaveAccess(AccessBackend.SHIZUKU_ROOT) {
    private val application = context.applicationContext
    private val writerGuard = application.getSharedPreferences("shizuku_writer_guard", Context.MODE_PRIVATE)
    private val stateWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "shizuku-status").apply { isDaemon = true } }
    private val ipcWorker = Executors.newCachedThreadPool { task -> Thread(task, "shizuku-io").apply { isDaemon = true } }
    private val lock = Any()
    private val ipcLock = Any()
    @Volatile private var pendingTask: Future<*>? = null
    @Volatile private var targetPackage: String? = null
    @Volatile private var targetUser: Int? = null
    @Volatile private var verifiedUid = false
    @Volatile private var remote: IShizukuSaveService? = null
    @Volatile private var binder: IBinder? = null
    @Volatile private var binderDeath: IBinder.DeathRecipient? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var disposed = false
    @Volatile private var writing = false
    @Volatile private var lostWriter = false
    @Volatile private var instance = ""
    @Volatile override var latestDiagnostic: AccessDiagnostic? = null
        private set
    @Volatile var statusString = "Shizuku 状态尚未检查"
        private set
    @Volatile var connected = false
        private set
    private val received = Shizuku.OnBinderReceivedListener { updateAsync() }
    private val dead = Shizuku.OnBinderDeadListener { disconnected("Shizuku 已停止，请使用 Root 方式启动后重试") }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, _ -> if (code == PERMISSION_REQUEST) updateAsync() }
    private val args: Shizuku.UserServiceArgs by lazy {
        @Suppress("DEPRECATION")
        val version = application.packageManager.getPackageInfo(application.packageName, 0).versionCode
        Shizuku.UserServiceArgs(ComponentName(application, ShizukuSaveService::class.java))
            .tag("local-save-root-v1").version(version).daemon(true).debuggable(false).processNameSuffix("save_root")
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        updateAsync()
    }
    private fun updateAsync() {
        if (!disposed) runCatching { stateWorker.execute { if (!disposed) { runCatching { checkStatus() }; onStateChanged() } } }
    }
    private fun disconnected(message: String) {
        if (writing) lostWriter = true
        remote = null; binder = null; connected = false; verifiedUid = false; statusString = message
        if (!disposed) onStateChanged()
    }
    private fun background() { check(Looper.myLooper() != Looper.getMainLooper()) { "Shizuku 文件操作必须在后台线程执行" } }
    /** Cached UI text is updated only by background SDK checks. */
    fun checkStatus(): String {
        background()
        statusString = try {
            val running = Shizuku.pingBinder()
            val version = if (running && !Shizuku.isPreV11()) Shizuku.getVersion() else 0
            val uid = if (version >= 13) Shizuku.getUid() else null
            val granted = uid == 0 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ShizukuStatus(running, version, uid, granted, uid == 0 && !granted && Shizuku.shouldShowRequestPermissionRationale(), connected).text
        } catch (_: Exception) { "Shizuku 状态读取失败，请启动或重新授权后重试" }
        return statusString
    }
    /** Called only by an explicit permission button, never by ensureReady or a file operation. */
    fun requestPermission() {
        background()
        check(!disposed)
        if (!Shizuku.pingBinder()) throw AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, checkStatus())
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, checkStatus())
        if (Shizuku.getUid() != 0) throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, ShizukuStatus.ADB_MESSAGE)
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { checkStatus(); onStateChanged(); return }
        if (Shizuku.shouldShowRequestPermissionRationale()) throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, checkStatus())
        Shizuku.requestPermission(PERMISSION_REQUEST)
    }
    override fun ensureReady() {
        background(); checkCancellation(); check(!disposed) { "Shizuku 通道已关闭" }
        checkStatus()
        if (!Shizuku.pingBinder()) throw AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, statusString)
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13 || Shizuku.getUid() != 0 ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, statusString)
        if (remote != null && binder?.isBinderAlive == true && verifiedUid && instance.isNotEmpty()) return
        synchronized(lock) {
            if (remote != null && binder?.isBinderAlive == true && verifiedUid && instance.isNotEmpty()) return
            val latch = CountDownLatch(1)
            var bindError: Throwable? = null
            val callback = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    synchronized(lock) {
                        if (connection !== this || disposed) return
                        try {
                            val watcher = IBinder.DeathRecipient {
                                if (binder === service) disconnected("Shizuku UserService 已断开，请重新连接")
                            }
                            service.linkToDeath(watcher, 0)
                            binderDeath = watcher; binder = service; remote = IShizukuSaveService.Stub.asInterface(service)
                            statusString = "Shizuku UserService 已连接，实际身份等待检查"
                        } catch (error: Exception) { bindError = error }
                        latch.countDown()
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    if (connection === this) disconnected("Shizuku UserService 已断开，请重新连接")
                    latch.countDown()
                }
            }
            connection = callback
            try {
                timed("绑定 UserService") { Shizuku.bindUserService(args, callback) }
            } catch (error: Exception) { connection = null; throw failure("绑定 UserService", error) }
            // Do not hold the callback's lock while waiting for the main-thread callback.
            // The actual wait occurs below, outside this synchronized block.
            binding = Binding(latch, callback) { bindError }
        }
        val pending = binding ?: return
        if (!pending.latch.await(BIND_SECONDS, TimeUnit.SECONDS) || remote == null) {
            detach(false)
            throw failure("绑定 UserService", pending.error() ?: TimeoutException("Shizuku UserService 连接超时"))
        }
        scalar("确认 Root 身份") { JsonPrimitive(it.rootUid()) }.also {
            if (it.jsonPrimitive.int != 0) throw AccessFailure(AccessFailureKind.ROOT_IDENTITY, "Shizuku UserService 实际身份不是 Root")
        }
        verifiedUid = true
        connected = true
        instance = timed("确认服务实例") { remote!!.instanceId() }.also {
            require(Regex("[a-f0-9-]{36}").matches(it)) { "Shizuku 服务实例标识无效" }
        }
        statusString = "Shizuku UserService 实际 UID=0；游戏目录尚未检查"
        binding = null
    }
    private data class Binding(val latch: CountDownLatch, val callback: ServiceConnection, val error: () -> Throwable?)
    @Volatile private var binding: Binding? = null

    private fun decode(text: String): JsonElement {
        try {
            val reply = ShizukuWire.decode(text)
            latestDiagnostic = reply.diagnostic
            return reply.value
        } catch (error: AccessFailure) { latestDiagnostic = error.diagnostic; throw error }
    }
    private fun failure(stage: String, error: Throwable, uncertain: Boolean = false, elapsed: Long = 0): AccessFailure {
        val cause = if (error is java.util.concurrent.ExecutionException) error.cause ?: error else error
        if (cause is AccessFailure) return cause
        val timeout = cause is TimeoutException
        statusString = if (timeout) "Shizuku 操作超时，请检查授权和服务状态" else "Shizuku 文件通道中断，请重新检查服务状态"
        val diagnostic = AccessDiagnostic(backend, stage, targetPackage, targetUser,
            if (verifiedUid) "uid=0（UserService 已验证）" else "UserService 身份尚未验证", null, elapsed,
            "传输错误：" + ShizukuWire.sanitize(cause.message ?: cause.javaClass.simpleName))
        latestDiagnostic = diagnostic
        return AccessFailure(if (timeout) AccessFailureKind.TIMEOUT else AccessFailureKind.SESSION_DISCONNECTED,
            "$stage 未完成；$statusString", diagnostic, uncertain, cause)
    }
    private fun <T> timed(stage: String, writingOperation: Boolean = false, cleanup: () -> Unit = {}, block: () -> T): T {
        background(); checkCancellation()
        val started = System.nanoTime()
        val future = synchronized(ipcLock) {
            if (pendingTask?.isDone == false) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE,
                "Shizuku 上一次传输尚未结束，请等待后重试", latestDiagnostic, writerGuard.contains("instance"))
            ipcWorker.submit<T> { block() }.also { pendingTask = it }
        }
        try { return future.get(OPERATION_SECONDS, TimeUnit.SECONDS) }
        catch (error: Exception) { cleanup(); throw failure(stage, error, writingOperation, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)) }
        finally { synchronized(ipcLock) { if (future.isDone && pendingTask === future) pendingTask = null } }
        // Binder calls are not interruptible. Never cancel or kill a running remote write.
    }
    private fun scalar(stage: String, block: (IShizukuSaveService) -> JsonElement): JsonElement =
        timed(stage) { block(remote ?: throw AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, "Shizuku 未连接")) }
    private fun ready(): IShizukuSaveService { ensureReady(); return remote ?: error("Shizuku 未连接") }
    private fun target(packageName: String?, user: Int?) { targetPackage = packageName; targetUser = user }
    private fun pathTarget(path: String) {
        val match = Regex("^/data/(?:user/([0-9]+)|data)/([^/]+)").find(path)
        target(match?.groupValues?.get(2), match?.groupValues?.get(1)?.toIntOrNull() ?: 0)
    }
    override fun stop(packageName: String, user: Int) { ShizukuWire.target(packageName, user); target(packageName, user); val service = ready(); scalar("停止游戏") { decode(service.stop(packageName, user)) } }
    override fun exists(directory: String): Boolean { ShizukuWire.directory(directory); pathTarget(directory); val service = ready(); return scalar("检查目录") { decode(service.exists(directory)) }.jsonPrimitive.boolean }
    override fun list(root: String, source: SaveSource): List<String> { ShizukuWire.directory(root, true); pathTarget(root); val service = ready(); return scalar("列出存档") { decode(service.list(root, source.ordinal)) }.jsonArray.map { it.jsonPrimitive.content } }
    override fun packages(user: Int): String { require(user in 0..100000); target(null, user); val service = ready(); return scalar("列出应用") { decode(service.packages(user)) }.jsonPrimitive.content }
    override fun probePaths(packageName: String, user: Int): List<String> { ShizukuWire.target(packageName, user); target(packageName, user); val service = ready(); return scalar("探测存档") { decode(service.probePaths(packageName, user)) }.jsonArray.map { it.jsonPrimitive.content } }
    override fun version(packageName: String): String { ShizukuWire.target(packageName, 0); target(packageName, if (targetPackage == packageName) targetUser else null); val service = ready(); return scalar("读取版本") { decode(service.packageVersion(packageName)) }.jsonPrimitive.content }
    override fun backupPaths(packageName: String, user: Int): List<String> { ShizukuWire.target(packageName, user); target(packageName, user); val service = ready(); return scalar("列出备份文件") { decode(service.backupPaths(packageName, user)) }.jsonArray.map { it.jsonPrimitive.content } }
    override fun read(path: String): SaveFile = readFile(path, false)
    override fun readBackup(path: String): SaveFile = readFile(path, true)
    private fun readFile(path: String, backupOnly: Boolean): SaveFile {
        ShizukuWire.path(path, backupOnly)
        pathTarget(path)
        val service = ready()
        var descriptor: ParcelFileDescriptor? = null
        return timed("读取存档", cleanup = { runCatching { descriptor?.close() } }) {
            val pipe = service.read(path, backupOnly).also { descriptor = it }
            val packet = ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                StreamProtocol.read(input, UnlockEngine.MAX_BYTES).also { pipe.checkError() }
            }
            val metadata = decode(packet.metadata).jsonPrimitive.content
            ShizukuWire.file(metadata, packet.bytes).also { require(it.path == path) { "读取路径与请求不一致" } }
        }
    }
    override fun replace(file: SaveFile, bytes: ByteArray) {
        ShizukuWire.path(file.path); require(bytes.size in 1..UnlockEngine.MAX_BYTES)
        pathTarget(file.path)
        if (lostWriter || writerGuard.contains("instance")) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE, "先前 Shizuku 写入结果尚未确认，请先恢复未完成事务", latestDiagnostic, true)
        val service = ready()
        require(Regex("[a-f0-9-]{36}").matches(instance)) { "Shizuku 服务实例尚未确认，已停止写入" }
        check(writerGuard.edit().putString("instance", instance).putInt("boot", bootCount()).commit()) {
            "无法持久保存 Shizuku 写入状态，已停止写入"
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        writing = true
        val sender = ipcWorker.submit {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                var offset = 0
                while (offset < bytes.size) { val count = minOf(32768, bytes.size - offset); output.write(bytes, offset, count); offset += count }
                output.flush()
            }
        }
        try {
            val response = timed("写回存档", writingOperation = true, cleanup = { runCatching { pipe[1].closeWithError("写入客户端超时") } }) {
                val response = service.replace(ShizukuWire.metadata(file), pipe[0], bytes.size.toLong(), StreamProtocol.sha(bytes))
                sender.get(OPERATION_SECONDS, TimeUnit.SECONDS)
                response
            }
            try {
                require(decode(response).jsonPrimitive.boolean) { "Shizuku 写回确认结果无效" }
            } catch (error: AccessFailure) {
                if (!error.uncertainWrite) clearConfirmedWriter()
                throw error
            } catch (error: Exception) {
                // Malformed or missing response fields are an unknown outcome, never a confirmation.
                throw failure("写回返回校验", error, true)
            }
            clearConfirmedWriter()
        } finally { writing = false; runCatching { pipe[0].close() }; runCatching { pipe[1].close() } }
    }
    private fun clearConfirmedWriter() {
        if (!writerGuard.edit().remove("instance").remove("boot").commit()) throw AccessFailure(AccessFailureKind.IO,
            "写入结果已返回，但无法保存完成状态；请先确认恢复", latestDiagnostic, true)
        lostWriter = false
    }
    override fun assertIdleForRecovery() {
        val service = ready()
        val previousInstance = writerGuard.getString("instance", null)
        if (previousInstance != null) {
            val previousBoot = writerGuard.getInt("boot", -1)
            val currentBoot = bootCount()
            if (!ShizukuWriterFence.mayCheckIdle(previousInstance, instance, previousBoot, currentBoot)) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE,
                "写入期间 Shizuku 服务已消失，无法确认旧写入结束；请重启设备后再恢复，事务备份已保留", latestDiagnostic, true)
        } else if (lostWriter) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE,
            "Shizuku 写入结果尚未确认；请保留事务备份", latestDiagnostic, true)
        scalar("确认旧写入结束") { decode(service.assertIdle()).also { require(it.jsonPrimitive.boolean) } }
        check(writerGuard.edit().remove("instance").remove("boot").commit()) { "无法保存 Shizuku 恢复状态" }
        lostWriter = false
    }
    private fun bootCount(): Int = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun detach(remove: Boolean) {
        val callback = connection
        val previousBinder = binder
        val previousDeath = binderDeath
        connection = null; binding = null; connected = false; remote = null; verifiedUid = false
        runCatching { if (previousDeath != null) previousBinder?.unlinkToDeath(previousDeath, 0) }; binder = null; binderDeath = null
        if (callback != null) {
            val unbind = {
                runCatching {
                    // SDK remove=true waits for death to clear its connection cache. Clear it
                    // first so an immediate rebind cannot join the old death callback's set.
                    Shizuku.unbindUserService(args, callback, false)
                    if (remove) Shizuku.unbindUserService(args, null, true)
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) runCatching { stateWorker.execute(unbind) } else unbind()
        }
    }
    private fun releaseService() {
        val service = remote
        val mayRelease = !writing && !lostWriter && !writerGuard.contains("instance") &&
            pendingTask?.isDone != false && service != null
        val idle = mayRelease && runCatching {
            timed("释放服务前确认") { decode(service!!.assertIdle()).jsonPrimitive.boolean }
        }.getOrDefault(false)
        // remove=true is used only after the service and shell both confirm actual completion.
        detach(idle)
    }
    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) runCatching { stateWorker.execute { releaseService() } }
        else releaseService()
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        // Lifecycle callbacks can run on the UI thread; all remote work stays on this worker.
        stateWorker.execute {
            releaseService()
            stateWorker.shutdown(); ipcWorker.shutdown()
        }
    }
    companion object {
        private const val PERMISSION_REQUEST = 9137
        private const val BIND_SECONDS = 12L
        private const val OPERATION_SECONDS = 60L
    }
}
