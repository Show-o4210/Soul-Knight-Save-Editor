package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the shared scan/discovery/backup policy and repository with a selected transport. */
class RoutingTest {
    @get:Rule val temp = TemporaryFolder()

    private class Access(backend: AccessBackend) : BaseSaveAccess(backend) {
        val root = "/data/user/10/com.test.game"
        val files = linkedMapOf<String, SaveFile>()
        val calls = mutableListOf<String>()
        var unavailable: AccessFailure? = null
        var probeFailure: AccessFailure? = null
        init {
            val prefs = SaveFile("$root/shared_prefs/playerprefs.xml",
                """<map><int name="42_c0_unlock" value="0"/><int name="42_c0_skin7" value="-999"/><int name="OpenRijTest_42" value="1"/></map>""".toByteArray(),
                "1010100:1010100", "600", "u:object_r:app_data_file:s0:c1,c2")
            val item = prefs.copy(path = "$root/files/item_data_42_.data", bytes = ItemCodec.encode(buildJsonObject { put("AppVersion", 80600) }))
            val opaque = prefs.copy(path = "$root/files/local.data", bytes = byteArrayOf(1, 2, 3))
            listOf(prefs, item, opaque).forEach { files[it.path] = it }
        }
        override fun ensureReady() {
            checkCancellation()
            calls += "ready"
            unavailable?.let { throw it }
        }
        override fun stop(packageName: String, user: Int) {
            ensureReady()
            require(packageName == "com.test.game" && user == 10)
            calls += "stop:$packageName:$user"
        }
        override fun exists(directory: String): Boolean {
            require(directory == root)
            calls += "exists:$directory"
            return true
        }
        override fun list(root: String, source: SaveSource): List<String> {
            require(root == this.root)
            calls += "list:$root:${source.name}"
            return files.keys.filter { it.startsWith("$root/${source.directory}/") && source.filename.matches(it.substringAfterLast('/')) }
        }
        override fun read(path: String): SaveFile {
            require(path.startsWith("$root/"))
            calls += "read:$path"
            return files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
        }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            require(file.path.startsWith("$root/"))
            calls += "replace:${file.path}"
            files[file.path] = file.copy(bytes = bytes.copyOf())
        }
        override fun packages(user: Int): String {
            require(user == 10)
            calls += "packages:$user"
            return "package:com.test.game\npackage:com.assistant.app"
        }
        override fun probePaths(packageName: String, user: Int): List<String> {
            require(packageName == "com.test.game" && user == 10)
            calls += "probe:$packageName:$user"
            probeFailure?.let { throw it }
            return listOf("$root/shared_prefs/playerprefs.xml")
        }
        override fun version(packageName: String): String {
            require(packageName == "com.test.game")
            calls += "version:$packageName"
            return "8.6.0"
        }
        override fun backupPaths(packageName: String, user: Int): List<String> {
            require(packageName == "com.test.game" && user == 10)
            calls += "backup:$packageName:$user"
            return files.keys.filter { it.endsWith(".data") }
        }
        override fun readBackup(path: String): SaveFile {
            calls += "backup-read:$path"
            return read(path)
        }
        override fun assertIdleForRecovery() { calls += "idle" }
        override fun close() { calls += "close" }
    }

    @Test fun selectedBackendOwnsDiscoveryScanRefreshBackupWriteAndRestoreForUserTen() {
        for (backend in AccessBackend.entries) {
            val transports = AccessBackend.entries.associateWith(::Access)
            val selected = transports.getValue(backend)
            val other = transports.values.single { it !== selected }
            val snapshot = selected.scan("com.test.game", 10)
            assertEquals(10, snapshot.user)
            assertEquals(listOf("42"), EditEngine.accounts(snapshot))
            val pinned = PinnedSaveTarget.from(snapshot, "42")
            val refreshed = selected.refresh(pinned)
            assertEquals(snapshot.files.map { it.path }, refreshed.files.map { it.path })
            val found = mutableListOf<GameCandidate>()
            val result = selected.discover(10, "com.test.game", "com.assistant.app", { false }, {}, found::add)
            assertEquals(1, result.checked)
            assertEquals("com.test.game", found.single().packageName)
            val backup = selected.backup("com.test.game", 10)
            assertEquals(10, backup.user)
            assertEquals(selected.files.keys.toSet(), backup.files.map { it.path }.toSet())
            val repository = SaveRepository(temp.newFolder(), selected)
            val changed = snapshot.items.single()
            val plan = SavePlan(mapOf(changed.path to "synthetic-change".toByteArray()), listOf("transport regression"))
            val id = repository.apply(snapshot, plan)
            assertEquals(backend.id, repository.backendId(id))
            assertArrayEquals(plan.outputs.getValue(changed.path), selected.files.getValue(changed.path).bytes)
            repository.restore(id)
            assertArrayEquals(changed.bytes, selected.files.getValue(changed.path).bytes)
            assertEquals(changed.owner, selected.files.getValue(changed.path).owner)
            assertEquals(changed.mode, selected.files.getValue(changed.path).mode)
            assertEquals(changed.context, selected.files.getValue(changed.path).context)
            assertTrue(repository.pending().isEmpty())
            assertTrue(selected.calls.contains("stop:com.test.game:10"))
            assertTrue(selected.calls.contains("packages:10"))
            assertTrue(selected.calls.contains("backup:com.test.game:10"))
            assertTrue(selected.calls.contains("idle"))
            assertTrue(other.calls.isEmpty())
        }
    }

    @Test fun selectedUnavailableShizukuCannotFallBackToAvailableNativeBackend() {
        val native = Access(AccessBackend.NATIVE_ROOT)
        val shizuku = Access(AccessBackend.SHIZUKU_ROOT)
        shizuku.unavailable = AccessFailure(AccessFailureKind.ROOT_IDENTITY, "Shizuku is not running as root")
        assertThrows(AccessFailure::class.java) { shizuku.scan("com.test.game", 10) }
        assertEquals(listOf("ready"), shizuku.calls)
        assertTrue(native.calls.isEmpty())
        assertFalse(shizuku.calls.any { it.startsWith("read:") || it.startsWith("replace:") })
    }

    @Test fun connectionLossDuringDiscoveryAbortsWithoutSwitchingBackendOrPretendingCompletion() {
        val native = Access(AccessBackend.NATIVE_ROOT)
        val selected = Access(AccessBackend.SHIZUKU_ROOT)
        selected.probeFailure = AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, "Binder died")
        val found = mutableListOf<GameCandidate>()
        val progress = mutableListOf<DiscoveryProgress>()
        assertThrows(AccessFailure::class.java) {
            selected.discover(10, "com.test.game", "com.assistant.app", { false }, progress::add, found::add)
        }
        assertTrue(found.isEmpty())
        assertEquals(listOf(DiscoveryProgress(total = 1)), progress)
        assertTrue(native.calls.isEmpty())
        assertFalse(selected.calls.any { it.startsWith("read:") || it.startsWith("replace:") })
    }
}
