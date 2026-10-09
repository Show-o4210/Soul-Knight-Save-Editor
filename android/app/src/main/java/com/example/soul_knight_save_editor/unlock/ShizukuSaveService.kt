package com.example.soul_knight_save_editor.unlock

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** A Shizuku UserService Binder, deliberately not an Android Service. There is no command IPC. */
class ShizukuSaveService(context: Context) : IShizukuSaveService.Stub() {
    private val ownUid = context.packageManager.getApplicationInfo(context.packageName, 0).uid
    private val instance = java.util.UUID.randomUUID().toString()
    private val gate = ShizukuOperationGate()
    private val worker = Executors.newSingleThreadExecutor()
    private val storage = ShellSaveAccess(ProcessRootCommandExecutor(listOf("/system/bin/sh")), AccessBackend.SHIZUKU_ROOT)

    private fun authorize() {
        check(Binder.getCallingUid() == ownUid) { "仅允许本应用访问 Shizuku 存档服务" }
        check(Process.myUid() == 0) { "Shizuku 以 ADB / shell 身份运行；请在 Shizuku 中使用 Root 方式启动" }
    }
    private fun scalar(block: () -> JsonElement): String {
        authorize()
        gate.begin()
        return try { ShizukuWire.success(block(), storage.latestDiagnostic) }
        catch (error: Exception) { ShizukuWire.failure(error, storage.latestDiagnostic) }
        finally { gate.end() }
    }
    override fun rootUid(): Int { authorize(); return Process.myUid() }
    override fun instanceId(): String { authorize(); return instance }
    override fun stop(packageName: String, user: Int) = scalar {
        ShizukuWire.target(packageName, user); storage.stop(packageName, user); JsonPrimitive(true)
    }
    override fun exists(directory: String) = scalar {
        ShizukuWire.directory(directory); JsonPrimitive(storage.exists(directory))
    }
    override fun list(root: String, sourceId: Int) = scalar {
        ShizukuWire.directory(root, true)
        val source = SaveSource.entries.getOrNull(sourceId) ?: error("存档来源无效")
        JsonArray(storage.list(root, source).also { require(it.size <= 128) }.map(::JsonPrimitive))
    }
    override fun packages(user: Int) = scalar {
        require(user in 0..100000); JsonPrimitive(storage.packages(user))
    }
    override fun probePaths(packageName: String, user: Int) = scalar {
        ShizukuWire.target(packageName, user)
        JsonArray(storage.probePaths(packageName, user).map(::JsonPrimitive))
    }
    override fun packageVersion(packageName: String) = scalar {
        ShizukuWire.target(packageName, 0); JsonPrimitive(storage.version(packageName).take(80))
    }
    override fun backupPaths(packageName: String, user: Int) = scalar {
        ShizukuWire.target(packageName, user)
        JsonArray(storage.backupPaths(packageName, user).also { require(it.size < 128) }.map(::JsonPrimitive))
    }
    override fun read(path: String, backupOnly: Boolean): ParcelFileDescriptor {
        authorize()
        ShizukuWire.path(path, backupOnly)
        gate.begin()
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (error: Exception) { gate.end(); throw error }
        try {
            worker.execute {
                var released = false
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                        val file = try { if (backupOnly) storage.readBackup(path) else storage.read(path) }
                        catch (error: Exception) {
                            StreamProtocol.write(output, ShizukuWire.failure(error, storage.latestDiagnostic), byteArrayOf(0), UnlockEngine.MAX_BYTES)
                            gate.end(); released = true
                            return@use
                        }
                        val metadata = ShizukuWire.success(JsonPrimitive(ShizukuWire.metadata(file)), storage.latestDiagnostic)
                        StreamProtocol.write(output, metadata, file.bytes, UnlockEngine.MAX_BYTES)
                        // Release before closing the stream sends EOF to the next sequential caller.
                        gate.end(); released = true
                    }
                } catch (error: Exception) {
                    if (!released) { gate.end(); released = true }
                    runCatching { pipe[1].closeWithError("Shizuku 文件流中断") }
                }
                finally { if (!released) gate.end() }
            }
        } catch (error: Exception) { pipe.forEach { it.close() }; gate.end(); throw error }
        return pipe[0]
    }
    override fun replace(metadata: String, source: ParcelFileDescriptor, length: Long, sha256: String): String = source.use { scalar {
        val requested = ShizukuWire.file(metadata, byteArrayOf())
        ShizukuWire.path(requested.path)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            StreamProtocol.readRaw(input, length, sha256, UnlockEngine.MAX_BYTES).also { source.checkError() }
        }
        // Caller-provided mode/owner/context cannot grant a different permission or label.
        val live = storage.read(requested.path)
        require(live.owner == requested.owner && live.mode == requested.mode && live.context == requested.context) { "写回元数据与当前文件不一致，请重新扫描" }
        storage.replace(live, bytes)
        JsonPrimitive(true)
    } }
    override fun assertIdle(): String {
        authorize()
        return try { gate.assertIdle(); storage.assertIdleForRecovery(); ShizukuWire.success(JsonPrimitive(true), storage.latestDiagnostic) }
        catch (error: Exception) { ShizukuWire.failure(AccessFailure(AccessFailureKind.WRITE_NOT_IDLE,
            "Shizuku 上一次写入尚未确认结束，请等待后重试恢复", storage.latestDiagnostic, true, error), storage.latestDiagnostic) }
    }
    override fun diagnostics(): String { authorize(); return ShizukuWire.success(JsonNull, storage.latestDiagnostic) }
    override fun destroy() {
        check(Binder.getCallingUid() in setOf(0, ownUid)) { "不允许销毁 Shizuku 服务" }
        // Version changes must wait for the old writer, too. Never kill a process still replacing a save.
        Thread {
            gate.stopAndAwait()
            while (true) {
                try { storage.assertIdleForRecovery(); break }
                catch (_: Exception) { Thread.sleep(200) }
            }
            storage.close()
            worker.shutdown()
            exitProcess(0)
        }.start()
    }
}
