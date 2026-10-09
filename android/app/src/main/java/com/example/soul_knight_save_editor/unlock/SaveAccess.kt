package com.example.soul_knight_save_editor.unlock

import java.util.concurrent.TimeUnit

enum class AccessBackend(val id: String, val label: String) {
    NATIVE_ROOT("native-root", "原生 Root"), SHIZUKU_ROOT("shizuku-root", "Shizuku Root");
    companion object { fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: NATIVE_ROOT }
}

enum class AccessFailureKind {
    ROOT_IDENTITY, TARGET_ACCESS_DENIED, COMMAND_FAILED, SESSION_DISCONNECTED, TIMEOUT, IO, OUTPUT_LIMIT, WRITE_NOT_IDLE
}

/** Diagnostic data never contains commands, save paths, file bodies or account identifiers. */
data class AccessDiagnostic(val backend: AccessBackend, val stage: String, val packageName: String?, val user: Int?,
    val identity: String, val exitCode: Int?, val elapsedMillis: Long, val mergedOutput: String) {
    fun render(): String = "后端：${backend.label}\n阶段：$stage\n目标包：${packageName ?: "未指定"}\nAndroid 用户：${user ?: "未指定"}" +
        "\n实际身份：$identity\n退出码：${exitCode ?: "未取得"}\n耗时：${elapsedMillis} ms\n合并输出（已脱敏）：\n${mergedOutput.take(1536)}"
}

class AccessFailure(val kind: AccessFailureKind, message: String, val diagnostic: AccessDiagnostic? = null,
    val uncertainWrite: Boolean = false, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The selected instance owns scanning, backup reads and every transaction file operation. */
interface SaveAccess : DeviceStorage, AutoCloseable {
    val backend: AccessBackend
    override val backendId: String get() = backend.id
    val latestDiagnostic: AccessDiagnostic? get() = null
    fun ensureReady()
    fun scan(packageName: String, user: Int): SaveSnapshot
    fun refresh(target: PinnedSaveTarget): SaveSnapshot
    fun discover(user: Int, preferred: String, ownPackage: String, isCancelled: () -> Boolean,
        progress: (DiscoveryProgress) -> Unit, found: (GameCandidate) -> Unit): DiscoveryProgress
    fun backup(packageName: String, user: Int): BackupPayload
    override fun assertIdleForRecovery() {}
}

/** Shared recognition policy; the privileged service exposes only these fixed primitives. */
abstract class BaseSaveAccess(final override val backend: AccessBackend) : SaveAccess, SaveScanAccess {
    protected var cancellation: (() -> Boolean)? = null
    protected fun checkCancellation() { if (cancellation?.invoke() == true) throw DiscoveryCancelled() }
    abstract fun packages(user: Int): String
    abstract fun probePaths(packageName: String, user: Int): List<String>
    abstract fun version(packageName: String): String
    abstract fun backupPaths(packageName: String, user: Int): List<String>
    abstract fun readBackup(path: String): SaveFile

    final override fun scan(packageName: String, user: Int) = SaveScanner.scan(this, packageName, user)
    final override fun refresh(target: PinnedSaveTarget) = SaveScanner.refresh(this, target)
    final override fun discover(user: Int, preferred: String, ownPackage: String, isCancelled: () -> Boolean,
        progress: (DiscoveryProgress) -> Unit, found: (GameCandidate) -> Unit): DiscoveryProgress {
        require(user >= 0 && SaveLayout.packagePattern.matches(preferred) && SaveLayout.packagePattern.matches(ownPackage)) { "包名或 Android 用户编号无效" }
        cancellation = isCancelled
        try {
            ensureReady()
            val candidates = SaveDiscovery.packages(packages(user), preferred, ownPackage)
            require(candidates.size <= SaveDiscovery.MAX_PACKAGES) { "已安装应用超过搜索上限，请手动填写目标包名" }
            var state = DiscoveryProgress(total = candidates.size)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
            progress(state)
            for (pkg in candidates) {
                checkCancellation()
                if (System.nanoTime() >= deadline) return state
                try {
                    val paths = probePaths(pkg, user)
                    val evidence = linkedSetOf<String>()
                    var incomplete = paths.size > SaveDiscovery.MAX_PROBES
                    for (path in paths.take(SaveDiscovery.MAX_PROBES)) {
                        checkCancellation()
                        if (System.nanoTime() >= deadline) { incomplete = true; break }
                        try {
                            val name = path.substringAfterLast('/')
                            if (name.endsWith(".xml") && "XML 角色／皮肤特征" in evidence || name.startsWith("item_data") && "物品分片结构" in evidence) continue
                            val bytes = read(path).bytes
                            when {
                                name.endsWith(".xml") && SaveDiscovery.xml(bytes) -> evidence += "XML 角色／皮肤特征"
                                name == "game.data" && SaveDiscovery.game(bytes) -> evidence += "game 角色／皮肤结构"
                                EditEngine.itemAccount(path) != null && SaveDiscovery.item(bytes) -> evidence += "物品分片结构"
                            }
                        } catch (error: DiscoveryCancelled) { throw error }
                        catch (error: Exception) { rethrowConnectionFailure(error); incomplete = true }
                    }
                    if (evidence.isNotEmpty()) {
                        val version = try { version(pkg) } catch (error: Exception) { rethrowConnectionFailure(error); "未知版本" }
                        checkCancellation()
                        found(GameCandidate(pkg, version = version, evidence = evidence.toList()))
                    }
                    if (incomplete) state = state.copy(skipped = state.skipped + 1)
                } catch (error: DiscoveryCancelled) { throw error }
                catch (error: Exception) { rethrowConnectionFailure(error); state = state.copy(skipped = state.skipped + 1) }
                state = state.copy(checked = state.checked + 1)
                progress(state)
            }
            return state
        } finally { cancellation = null }
    }
    private fun rethrowConnectionFailure(error: Exception) {
        if (error is AccessFailure && error.kind in setOf(AccessFailureKind.ROOT_IDENTITY, AccessFailureKind.SESSION_DISCONNECTED,
                AccessFailureKind.TIMEOUT, AccessFailureKind.IO, AccessFailureKind.WRITE_NOT_IDLE)) throw error
    }
    final override fun backup(packageName: String, user: Int): BackupPayload {
        val snapshot = scan(packageName, user)
        val paths = backupPaths(packageName, user)
        require(paths.size < 128) { "本地文件过多，暂不创建备份" }
        val files = listOfNotNull(snapshot.prefs).toMutableList()
        var total = files.sumOf { it.bytes.size.toLong() }
        paths.forEach { path ->
            val file = readBackup(path)
            total += file.bytes.size
            require(total <= BackupArchive.MAX_TOTAL_BYTES) { "本地存档超过当前备份大小限制" }
            files += file
        }
        files.forEach { original -> require(readBackup(original.path).bytes.contentEquals(original.bytes)) { "备份期间存档发生变化，请保持游戏关闭后重试" } }
        return BackupPayload(packageName, user, files)
    }
}
