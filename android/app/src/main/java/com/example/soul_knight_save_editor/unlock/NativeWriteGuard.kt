package com.example.soul_knight_save_editor.unlock

/** A new app process cannot infer whether a previously detached native shell finished a write. */
class NativeWriteGuard(private val bootCount: () -> Int, load: () -> Int,
    private val persist: (Int?) -> Unit) {
    private val inheritedWriteBoot = load()
    fun checkInheritedWrite() {
        if (inheritedWriteBoot < 0) return
        val now = bootCount()
        if (now <= inheritedWriteBoot) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE,
            "此前原生 Root 写任务的结果尚未确认；请重启设备后，先恢复未完成事务", uncertainWrite = true)
    }
    fun replace(block: () -> Unit) {
        checkInheritedWrite()
        val epoch = bootCount()
        check(epoch >= 0) { "无法取得设备启动编号，已停止写入" }
        persist(epoch)
        var confirmed = false
        try { block(); confirmed = true }
        catch (error: Exception) {
            confirmed = generateSequence<Throwable>(error) { it.cause }.none { it is AccessFailure && it.uncertainWrite }
            throw error
        } finally { if (confirmed) persist(null) }
    }
    fun confirmIdle(block: () -> Unit) {
        checkInheritedWrite()
        block()
        persist(null)
    }
}

class GuardedNativeSaveAccess(private val native: SaveAccess, private val guard: NativeWriteGuard) : SaveAccess by native {
    override fun ensureReady() { guard.checkInheritedWrite(); native.ensureReady() }
    override fun assertIdleForRecovery() = guard.confirmIdle { native.assertIdleForRecovery() }
    override fun replace(file: SaveFile, bytes: ByteArray) = guard.replace { native.replace(file, bytes) }
    override fun stop(packageName: String, user: Int) { guard.checkInheritedWrite(); native.stop(packageName, user) }
    override fun scan(packageName: String, user: Int): SaveSnapshot { guard.checkInheritedWrite(); return native.scan(packageName, user) }
    override fun refresh(target: PinnedSaveTarget): SaveSnapshot { guard.checkInheritedWrite(); return native.refresh(target) }
    override fun discover(user: Int, preferred: String, ownPackage: String, isCancelled: () -> Boolean,
        progress: (DiscoveryProgress) -> Unit, found: (GameCandidate) -> Unit): DiscoveryProgress {
        guard.checkInheritedWrite()
        return native.discover(user, preferred, ownPackage, isCancelled, progress, found)
    }
    override fun backup(packageName: String, user: Int): BackupPayload { guard.checkInheritedWrite(); return native.backup(packageName, user) }
}
