package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Inject failures at the transport boundary, after the journal has become durable. */
class BackendSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    private class Device(
        override val backendId: String = AccessBackend.NATIVE_ROOT.id,
        val files: MutableMap<String, SaveFile> = linkedMapOf()
    ) : DeviceStorage {
        val operations = mutableListOf<String>()
        var attempts = 0
        var uncertainAt = -1
        var failAt = -1
        var commitBeforeUncertain = true
        var wrapUncertain = false
        var idle = true
        var readHook: ((String, SaveFile) -> SaveFile)? = null
        override fun assertIdleForRecovery() {
            operations += "idle"
            if (!idle) throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE, "write still active")
        }
        override fun stop(packageName: String, user: Int) { operations += "stop:$packageName:$user" }
        override fun read(path: String): SaveFile {
            operations += "read:$path"
            val snapshot = files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
            return readHook?.invoke(path, snapshot) ?: snapshot
        }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            operations += "replace:${file.path}"
            attempts++
            if (attempts == failAt) error("known operation failure")
            if (attempts != uncertainAt || commitBeforeUncertain) files[file.path] = file.copy(bytes = bytes.copyOf())
            if (attempts == uncertainAt) {
                val failure = AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, "lost result", uncertainWrite = true)
                if (wrapUncertain) throw IllegalStateException("wrapped IPC failure", failure)
                throw failure
            }
        }
    }

    private fun snapshot(device: Device): SaveSnapshot {
        val prefs = SaveFile("/data/user/10/com.test.game/shared_prefs/game.xml", "prefs-before".toByteArray(),
            "1010100:1010100", "600", "u:object_r:app_data_file:s0:c1,c2")
        val game = prefs.copy(path = "/data/user/10/com.test.game/files/game.data", bytes = "game-before".toByteArray())
        device.files[prefs.path] = prefs
        device.files[game.path] = game
        return SaveSnapshot("com.test.game", 10, prefs, game)
    }
    private fun plan(snapshot: SaveSnapshot) = SavePlan(
        snapshot.files.associate { it.path to "${it.path.substringAfterLast('/')}-after".toByteArray() }, listOf("synthetic"))
    private fun assertOriginals(snapshot: SaveSnapshot, device: Device) {
        snapshot.files.forEach { before ->
            val live = device.files.getValue(before.path)
            assertArrayEquals(before.bytes, live.bytes)
            assertEquals(before.owner, live.owner)
            assertEquals(before.mode, live.mode)
            assertEquals(before.context, live.context)
        }
    }

    @Test fun uncertainWriteAfterReplacementRetainsJournalAndNeverRetriesOrRollsBack() {
        val device = Device(AccessBackend.SHIZUKU_ROOT.id)
        val snapshot = snapshot(device)
        val plan = plan(snapshot)
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        device.uncertainAt = 1
        val failure = assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
        assertTrue(failure.message.orEmpty().contains("尚未确定"))
        assertEquals(1, device.attempts)
        assertTrue(device.operations.last().startsWith("replace:"))
        assertFalse(device.operations.contains("idle"))
        assertArrayEquals(plan.outputs.getValue(snapshot.prefs!!.path), device.files.getValue(snapshot.prefs.path).bytes)
        assertArrayEquals(snapshot.game!!.bytes, device.files.getValue(snapshot.game.path).bytes)
        val id = repository.pending().single()
        assertEquals(AccessBackend.SHIZUKU_ROOT.id, repository.backendId(id))
        val saved = repository.originals(id)
        snapshot.files.forEach { original -> assertArrayEquals(original.bytes, saved.files.single { it.path == original.path }.bytes) }
        val operations = device.operations.toList()
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, plan) }
        assertEquals(operations, device.operations)
        assertEquals(listOf(id), SaveRepository(directory, device).pending())
    }

    @Test fun wrappedUncertainFailureBeforeReplacementStillRequiresExplicitRecovery() {
        val device = Device()
        val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        device.uncertainAt = 1
        device.commitBeforeUncertain = false
        device.wrapUncertain = true
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan(snapshot)) }
        assertEquals(1, device.attempts)
        assertOriginals(snapshot, device)
        assertFalse(device.operations.contains("idle"))
        val id = repository.pending().single()
        device.uncertainAt = -1
        repository.recover(id)
        assertEquals(1, device.attempts)
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun recoveryCannotAccessFilesUntilBackendConfirmsWriterIdle() {
        val device = Device()
        val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        device.uncertainAt = 1
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan(snapshot)) }
        val id = repository.pending().single()
        device.operations.clear()
        device.idle = false
        assertThrows(AccessFailure::class.java) { repository.recover(id) }
        assertEquals(listOf("idle"), device.operations)
        assertEquals(1, device.attempts)
        assertEquals(listOf(id), repository.pending())
    }

    @Test fun pendingTransactionRejectsDifferentBackendBeforeAnyDeviceOperation() {
        val native = Device()
        val snapshot = snapshot(native)
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, native)
        native.uncertainAt = 1
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan(snapshot)) }
        val id = repository.pending().single()
        val shizuku = Device(AccessBackend.SHIZUKU_ROOT.id, native.files)
        val otherRepository = SaveRepository(directory, shizuku)
        assertThrows(IllegalArgumentException::class.java) { otherRepository.recover(id) }
        assertTrue(shizuku.operations.isEmpty())
        assertEquals(0, shizuku.attempts)
        assertEquals(listOf(id), otherRepository.pending())
    }

    @Test fun legacyManifestWithoutBackendRecoversUsingNativeRootAndPreservesMetadata() {
        val device = Device()
        val snapshot = snapshot(device)
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        val id = repository.apply(snapshot, plan(snapshot))
        val manifest = File(directory, "$id/manifest.json")
        val metadata = Json.parseToJsonElement(manifest.readText()).jsonObject
        manifest.writeText(JsonObject(metadata - "backend").toString())
        File(directory, "$id/state").appendText("\napplying\n")
        val restarted = SaveRepository(directory, device)
        assertEquals(AccessBackend.NATIVE_ROOT.id, restarted.backendId(id))
        assertEquals(listOf(id), restarted.pending())
        restarted.recover(id)
        assertOriginals(snapshot, device)
        assertTrue(restarted.pending().isEmpty())
        assertTrue(device.operations.contains("stop:com.test.game:10"))
    }

    @Test fun interruptedManualRestoreIsPendingAndCanResumeOnlyWithItsRestoreBackend() {
        val native = Device()
        val snapshot = snapshot(native)
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, native)
        val id = repository.apply(snapshot, plan(snapshot))
        val shizuku = Device(AccessBackend.SHIZUKU_ROOT.id, native.files)
        val selected = SaveRepository(directory, shizuku)
        shizuku.failAt = 2
        assertThrows(IllegalStateException::class.java) { selected.restore(id) }
        assertEquals(2, shizuku.attempts)
        assertEquals(listOf(id), selected.pending())
        assertEquals(AccessBackend.SHIZUKU_ROOT.id, selected.backendId(id))
        assertArrayEquals(snapshot.game!!.bytes, shizuku.files.getValue(snapshot.game.path).bytes)
        assertFalse(snapshot.prefs!!.bytes.contentEquals(shizuku.files.getValue(snapshot.prefs.path).bytes))
        native.operations.clear()
        assertThrows(IllegalArgumentException::class.java) { repository.recover(id) }
        assertTrue(native.operations.isEmpty())
        val operations = shizuku.operations.toList()
        assertThrows(IllegalArgumentException::class.java) { selected.apply(snapshot, plan(snapshot)) }
        assertEquals(operations, shizuku.operations)
        shizuku.failAt = -1
        SaveRepository(directory, shizuku).recover(id)
        assertOriginals(snapshot, shizuku)
        assertTrue(selected.pending().isEmpty())
        assertEquals(3, shizuku.attempts)
    }

    @Test fun manualRestoreChecksIdleBeforeStoppingOrReadingCompletedTransaction() {
        val device = Device()
        val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, plan(snapshot))
        device.operations.clear()
        device.idle = false
        assertThrows(AccessFailure::class.java) { repository.restore(id) }
        assertEquals(listOf("idle"), device.operations)
        assertEquals(listOf(id), repository.completed())
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun driftBetweenRestorePreflightAndReplacementPreservesNewProgressAndPendingJournal() {
        val device = Device()
        val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, plan(snapshot))
        val game = snapshot.game!!
        val outsideProgress = "fresh progress outside this transaction".toByteArray()
        var gameReads = 0
        device.readHook = { path, live ->
            if (path == game.path && ++gameReads == 2) {
                live.copy(bytes = outsideProgress).also { device.files[path] = it }
            } else live
        }
        val attempts = device.attempts
        assertThrows(IllegalArgumentException::class.java) { repository.restore(id) }
        assertEquals(attempts, device.attempts)
        assertArrayEquals(outsideProgress, device.files.getValue(game.path).bytes)
        assertEquals(listOf(id), repository.pending())
        val operations = device.operations.toList()
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, plan(snapshot)) }
        assertEquals(operations, device.operations)
        device.readHook = null
        assertThrows(IllegalArgumentException::class.java) { repository.recover(id) }
        assertArrayEquals(outsideProgress, device.files.getValue(game.path).bytes)
        assertEquals(listOf(id), repository.pending())
    }
}
