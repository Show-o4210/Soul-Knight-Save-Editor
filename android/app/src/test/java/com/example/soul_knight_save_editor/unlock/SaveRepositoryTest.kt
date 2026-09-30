package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private class SimulatedDeath : Error()
    private class Device : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        var attempts = 0
        var failAt = -1
        var dieAt = -1
        var stops = 0
        override fun stop(packageName: String, user: Int) { stops++ }
        override fun read(path: String) = files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            attempts++
            if (attempts == failAt) error("injected failure")
            files[file.path] = file.copy(bytes = bytes.copyOf())
            if (attempts == dieAt) throw SimulatedDeath()
        }
    }
    private fun snapshot(device: Device): SaveSnapshot {
        val prefs = SaveFile("/data/user/0/com.test.game/shared_prefs/game.xml", "prefs-before".toByteArray(), "10100:10100", "600", "u:object_r:app_data_file:s0")
        val game = prefs.copy(path = "/data/user/0/com.test.game/files/game.data", bytes = "game-before".toByteArray())
        device.files[prefs.path] = prefs; device.files[game.path] = game
        return SaveSnapshot("com.test.game", 0, prefs, game)
    }
    private fun patch() = UnlockPatch("game-after".toByteArray(), "prefs-after".toByteArray(), listOf("test"))
    @Test fun fourthFileFailureRestoresCharacterItemAndWeaponChangesTogether() {
        val device = Device()
        val base = snapshot(device)
        val item = base.game!!.copy(path = base.game.path.replace("game.data", "item_data_42_.data"), bytes = "item-before".toByteArray())
        val statistic = item.copy(path = item.path.replace("item_data", "statistic"), bytes = "stat-before".toByteArray())
        device.files[item.path] = item
        device.files[statistic.path] = statistic
        val snapshot = base.copy(items = listOf(item), statistics = listOf(statistic))
        val repository = SaveRepository(temp.newFolder(), device)
        device.failAt = 4
        val plan = SavePlan(snapshot.files.associate { it.path to "after".toByteArray() }, listOf("combined"))
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
        snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun versionSourceDriftPreventsWeaponWriteAndStatisticsOriginalsCanBeRestored() {
        val device = Device()
        val item = SaveFile("/data/user/0/com.test.game/files/item_data_42_.data", "version-source".toByteArray(), "10100:10100", "600", "")
        val stat = item.copy(path = item.path.replace("item_data", "statistic"), bytes = "stat-before".toByteArray())
        val snapshot = SaveSnapshot("com.test.game", 0, null, null, listOf(item), listOf(stat))
        snapshot.files.forEach { device.files[it.path] = it }
        val repository = SaveRepository(temp.newFolder(), device)
        val plan = SavePlan(mapOf(stat.path to "stat-after".toByteArray()), listOf("weapon"))
        device.files[item.path] = item.copy(bytes = "changed-version".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, plan) }
        assertEquals(0, device.attempts)
        device.files[item.path] = item
        val id = repository.apply(snapshot, plan)
        assertEquals(listOf(stat.path), repository.originals(id).files.map { it.path })
        repository.restore(id)
        snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
    }
    @Test fun historyFiltersBySelectedGameVersion() {
        val device = Device()
        val first = snapshot(device)
        val second = first.copy(packageName = "com.other.game", prefs = first.prefs!!.copy(path = first.prefs.path.replace("com.test.game", "com.other.game")),
            game = first.game!!.copy(path = first.game.path.replace("com.test.game", "com.other.game")))
        second.files.forEach { device.files[it.path] = it }
        val repository = SaveRepository(temp.newFolder(), device)
        val a = repository.apply(first, patch())
        val b = repository.apply(second, patch())
        assertEquals(listOf(a), repository.completed(first.packageName))
        assertEquals(listOf(b), repository.exportable(second.packageName))
        assertEquals(first.packageName, repository.packageName(a))
        assertTrue(repository.completed("com.unknown.game").isEmpty())
    }
    @Test fun itemOnlyTransactionWorksWithoutLegacyFiles() {
        val device = Device()
        val item = SaveFile("/data/user/0/com.test.game/files/item_data_42_.data", "item-before".toByteArray(), "10100:10100", "600", "")
        device.files[item.path] = item
        val snapshot = SaveSnapshot("com.test.game", 0, null, null, listOf(item))
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, SavePlan(mapOf(item.path to "item-after".toByteArray()), listOf("item")))
        assertEquals("item-after", String(device.read(item.path).bytes))
        repository.restore(id)
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
    }
    @Test fun thirdFileFailureRestoresXmlGameAndItemTogether() {
        val device = Device()
        val original = snapshot(device)
        val item = original.game!!.copy(path = original.game.path.replace("game.data", "item_data_42_.data"), bytes = "item-before".toByteArray())
        device.files[item.path] = item
        val snapshot = original.copy(items = listOf(item))
        val repository = SaveRepository(temp.newFolder(), device)
        device.failAt = 3
        val plan = SavePlan(snapshot.files.associate { it.path to "changed".toByteArray() }, listOf("all"))
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
        snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun arbitraryPlanCannotWriteOutsideSnapshot() {
        val device = Device(); val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, SavePlan(mapOf("other" to byteArrayOf(1)), listOf("invalid"))) }
        assertEquals(0, device.attempts)
    }
    @Test fun exportOriginalsRetainsBeforeBytesWithoutTouchingDevice() {
        val device = Device(); val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, patch())
        val attempts = device.attempts
        val stops = device.stops
        val payload = repository.originals(id)
        assertEquals("pre-change", payload.kind)
        assertEquals(snapshot.packageName, payload.packageName)
        snapshot.files.forEach { source -> assertArrayEquals(source.bytes, payload.files.single { it.path == source.path }.bytes) }
        assertEquals(attempts, device.attempts)
        assertEquals(stops, device.stops)
    }
    @Test fun exportCorruptedOriginalsIsRefused() {
        val device = Device(); val snapshot = snapshot(device); val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        val id = repository.apply(snapshot, patch())
        java.io.File(directory, "$id/0.before").writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { repository.originals(id) }
    }
    @Test fun backsUpWritesAndRestoresBothFiles() {
        val device = Device(); val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, patch())
        assertEquals(listOf(id), repository.completed())
        assertEquals("game-after", String(device.read(snapshot.game!!.path).bytes))
        repository.restore(id)
        assertArrayEquals(snapshot.game.bytes, device.read(snapshot.game.path).bytes)
        assertArrayEquals(snapshot.prefs!!.bytes, device.read(snapshot.prefs.path).bytes)
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun scanToWriteDriftRefusesAnyWrite() {
        val device = Device(); val snapshot = snapshot(device)
        device.files[snapshot.prefs!!.path] = snapshot.prefs.copy(bytes = "new progress".toByteArray())
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, patch()) }
        assertEquals(0, device.attempts)
        assertTrue(repository.completed().isEmpty())
    }
    @Test fun secondFileFailureRollsBackFirstFile() {
        val device = Device(); val snapshot = snapshot(device); device.failAt = 2
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, patch()) }
        snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun processDeathAfterRenameRecoveredOnNextRepositoryInstance() {
        val device = Device(); val snapshot = snapshot(device); device.dieAt = 1
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        assertThrows(SimulatedDeath::class.java) { repository.apply(snapshot, patch()) }
        val restarted = SaveRepository(directory, device)
        val id = restarted.pending().single()
        device.dieAt = -1
        restarted.recover(id)
        snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(restarted.pending().isEmpty())
    }
    @Test fun restoreDoesNotOverwritePostGameProgress() {
        val device = Device(); val snapshot = snapshot(device)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, patch())
        device.files[snapshot.prefs!!.path] = snapshot.prefs.copy(bytes = "new progress".toByteArray())
        val attempts = device.attempts
        assertThrows(IllegalArgumentException::class.java) { repository.restore(id) }
        assertEquals(attempts, device.attempts)
        assertEquals("new progress", String(device.read(snapshot.prefs.path).bytes))
    }
    @Test fun pendingJournalBlocksNewWrites() {
        val device = Device(); val snapshot = snapshot(device); device.dieAt = 1
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(SimulatedDeath::class.java) { repository.apply(snapshot, patch()) }
        assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, patch()) }
    }
    @Test fun tamperedBackupRefusesRestore() {
        val device = Device(); val snapshot = snapshot(device)
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        val id = repository.apply(snapshot, patch())
        java.io.File(directory, "$id/0.before").writeText("tampered")
        assertThrows(IllegalArgumentException::class.java) { repository.restore(id) }
        assertEquals("prefs-after", String(device.read(snapshot.prefs!!.path).bytes))
    }
    @Test fun xmlOnlyTransactionsWork() {
        val device = Device(); val snapshot = snapshot(device).copy(game = null)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, patch().copy(game = null))
        assertEquals(1, device.attempts)
        repository.restore(id)
        assertArrayEquals(snapshot.prefs!!.bytes, device.read(snapshot.prefs.path).bytes)
    }
}
