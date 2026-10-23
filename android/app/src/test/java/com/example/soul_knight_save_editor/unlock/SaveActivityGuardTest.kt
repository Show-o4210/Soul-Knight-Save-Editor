package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class SaveActivityGuardTest {
    @get:Rule val temp = TemporaryFolder()
    private val pkg = "com.test.game"
    private val root = "/data/user/0/$pkg"
    private inner class Access(backend: AccessBackend) : BaseSaveAccess(backend) {
        val files = linkedMapOf<String, SaveFile>()
        var running = false
        var reads = 0
        var writes = 0
        var stopCalls = 0
        var time = 1L
        var advanceOnRead = 0L
        var mutateAfterRead: ((Int) -> Unit)? = null
        var onStop: ((Int) -> Unit)? = null
        override fun readNowNanos() = time
        override fun ensureReady() {}
        override fun stop(packageName: String, user: Int) {
            checkCancellation(); stopCalls++; onStop?.invoke(stopCalls)
            if (running && packageName == pkg) throw AccessFailure(AccessFailureKind.GAME_RUNNING, "请保存并彻底退出游戏后重试")
        }
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource) = files.keys.filter {
            it.startsWith("$root/${source.directory}/") && source.filename.matches(it.substringAfterLast('/'))
        }
        override fun read(path: String): SaveFile {
            checkCancellation(); time += advanceOnRead
            val file = files.getValue(path).copy(bytes = files.getValue(path).bytes.copyOf())
            reads++; mutateAfterRead?.invoke(reads)
            return file
        }
        override fun readBackup(path: String) = read(path)
        override fun replace(file: SaveFile, bytes: ByteArray) { writes++; files[file.path] = file.copy(bytes = bytes) }
        override fun packages(user: Int) = "package:$pkg\npackage:com.other.game"
        override fun probePaths(packageName: String, user: Int) = if (packageName == pkg) files.keys.toList() else emptyList()
        override fun version(packageName: String) = "8.7.0"
        override fun backupPaths(packageName: String, user: Int) = files.keys.filter { it.endsWith(".data") }
        override fun close() {}
        fun add(name: String, value: Int = 1): SaveFile {
            val path = "$root/files/item_data_${name}_.data"
            return SaveFile(path, ItemCodec.encode(buildJsonObject { put("AppVersion", 80700); put("value", value) }), "1000:1000", "600", "label")
                .also { files[path] = it }
        }
    }

    @Test fun runningGameBlocksScanRefreshFreeExportBackupAndApplyBeforeReadingOrWriting() {
        AccessBackend.entries.forEach { backend ->
            val access = Access(backend)
            val item = access.add("42")
            val snapshot = access.scan(pkg, 0)
            val target = PinnedSaveTarget.from(snapshot, "42")
            access.running = true
            access.reads = 0
            val repository = SaveRepository(temp.newFolder(), access)
            for (operation in listOf<() -> Unit>(
                { access.scan(pkg, 0) }, { access.refresh(target) }, { access.freeSnapshot(pkg, 0) },
                { access.backup(pkg, 0) }, { access.verifySnapshot(snapshot) }, { repository.restore(BackupPayload(pkg, 0, snapshot.files)) },
                { repository.apply(snapshot, SavePlan(mapOf(item.path to byteArrayOf(2)), listOf("edit"))) }
            )) {
                assertEquals(AccessFailureKind.GAME_RUNNING, assertThrows(AccessFailure::class.java) { operation() }.kind)
            }
            assertEquals(0, access.reads)
            assertEquals(0, access.writes)
            assertTrue(repository.pending().isEmpty())
            access.running = false
            assertEquals("42", EditEngine.accounts(access.scan(pkg, 0)).single())
        }
    }

    @Test fun changingAnEarlierShardDuringAnotherReadRejectsTheEntireSnapshot() {
        for (operation in listOf("scan", "free", "backup")) {
            val access = Access(AccessBackend.NATIVE_ROOT)
            val earlier = access.add("42")
            access.add("99")
            access.mutateAfterRead = { count -> if (count == 2) access.files[earlier.path] = earlier.copy(bytes = byteArrayOf(9)) }
            assertThrows(SaveSnapshotChanged::class.java) {
                when (operation) { "free" -> access.freeSnapshot(pkg, 0); "backup" -> access.backup(pkg, 0); else -> access.scan(pkg, 0) }
            }
            assertEquals(0, access.writes)
        }
    }

    @Test fun gameStartingDuringReadRejectsSnapshotAndReadDeadlineCanBeRetried() {
        val access = Access(AccessBackend.SHIZUKU_ROOT)
        access.add("42")
        access.mutateAfterRead = { access.running = true }
        assertEquals(AccessFailureKind.GAME_RUNNING, assertThrows(AccessFailure::class.java) { access.scan(pkg, 0) }.kind)
        access.running = false; access.mutateAfterRead = null
        access.advanceOnRead = TimeUnit.MINUTES.toNanos(3)
        assertEquals(AccessFailureKind.TIMEOUT, assertThrows(AccessFailure::class.java) { access.freeSnapshot(pkg, 0) }.kind)
        access.advanceOnRead = 0
        assertEquals(1, access.freeSnapshot(pkg, 0).files.size)
    }

    @Test fun restartDuringApplyPreservesPartialTransactionAndRestorationWaitsForExit() {
        val access = Access(AccessBackend.NATIVE_ROOT)
        access.add("42"); access.add("99")
        val snapshot = access.scan(pkg, 0)
        val plan = SavePlan(snapshot.files.associate { it.path to byteArrayOf(7) }, listOf("two files"))
        val repository = SaveRepository(temp.newFolder(), access)
        access.onStop = { if (access.writes > 0) access.running = true }
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
        assertEquals(1, access.writes)
        val pending = repository.pending().single()
        assertEquals(AccessFailureKind.GAME_RUNNING, assertThrows(AccessFailure::class.java) { repository.recover(pending) }.kind)
        assertEquals(1, access.writes)
        access.running = false; access.onStop = null
        repository.recover(pending)
        snapshot.files.forEach { assertArrayEquals(it.bytes, access.files.getValue(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun accessFailureInvalidatesLiveDraftsButKeepsExternalEditingOriginals() {
        val snapshot = SaveSnapshot(pkg, 0, null, null)
        val session = FreeEditSession("kept", "content://export", snapshot, emptyList())
        val state = AssistantState(snapshot = snapshot, freeSession = session,
            preview = SavePlan(mapOf("file" to byteArrayOf(1)), listOf("old")), previewMode = AssistantMode.QUICK,
            quickWeaponEvolution = WeaponEvolutionSelection(allMax = true), quickMythic = MythicSelection(maxAll = true))
        val cleared = state.afterStorageFailure(AccessFailure(AccessFailureKind.GAME_RUNNING, "exit first"))
        assertNull(cleared.snapshot)
        assertNull(cleared.preview)
        assertFalse(cleared.quickWeaponEvolution.hasChanges)
        assertFalse(cleared.quickMythic.hasChanges)
        assertSame(session, cleared.freeSession)
    }

    @Test fun oldFreeEditingOriginalsMustStillMatchBeforePreviewing() {
        val access = Access(AccessBackend.NATIVE_ROOT)
        val original = access.add("42")
        val snapshot = access.freeSnapshot(pkg, 0)
        access.files[original.path] = original.copy(bytes = byteArrayOf(9))
        assertThrows(SaveSnapshotChanged::class.java) { access.verifySnapshot(snapshot) }
        assertEquals(0, access.writes)
    }

    @Test fun discoverySkipsRunningCandidatesWithoutReadingTheirFiles() {
        val access = Access(AccessBackend.NATIVE_ROOT)
        access.add("42"); access.running = true
        val found = mutableListOf<GameCandidate>()
        val progress = access.discover(0, pkg, "com.assistant.app", { false }, {}, found::add)
        assertEquals(2, progress.checked)
        assertEquals(1, progress.running)
        assertTrue(found.isEmpty())
        assertEquals(0, access.reads)
    }
}
