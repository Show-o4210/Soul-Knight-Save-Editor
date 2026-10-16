package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreeEditTransactionTest {
    @get:Rule val temp = TemporaryFolder()
    private class Death : Error()
    private class Device : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        var writes = 0
        var failAt = -1
        var dieAt = -1
        override fun stop(packageName: String, user: Int) {}
        override fun read(path: String) = files.getValue(path).copy(bytes = files.getValue(path).bytes.copyOf())
        override fun replace(file: SaveFile, bytes: ByteArray) {
            writes++
            if (writes == failAt) error("failure")
            files[file.path] = file.copy(bytes = bytes.copyOf())
            if (writes == dieAt) throw Death()
        }
    }
    private fun exported(device: Device): Pair<FreeEditSession, SavePlan> {
        val source = SaveFile("/data/user/0/com.test.game/files/item_data_42_.data", ItemCodec.encode(kotlinx.serialization.json.Json.parseToJsonElement("""{"custom":1}""").jsonObject), "10100:10100", "600", "label")
        val prefs = source.copy(path = "/data/user/0/com.test.game/shared_prefs/game.xml", bytes = """<map><int name="OpenRijTest_42" value="1"/></map>""".toByteArray())
        device.files[source.path] = source
        device.files[prefs.path] = prefs
        val snapshot = SaveSnapshot("com.test.game", 0, prefs, null, listOf(source))
        val (session, docs) = FreeEditEngine.create(snapshot, setOf(source.path), null, "content://workspace/session")
        val doc = FreeDocument("edited", docs.single().name, """{"custom":2}""".toByteArray())
        return session to FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(doc)))
    }
    @Test fun gameChangesSinceExportRejectWriteWithoutOverwritingNewProgress() {
        val device = Device()
        val (session, plan) = exported(device)
        val source = session.entries.single().path
        device.files[source] = device.files.getValue(source).copy(bytes = "new game progress".toByteArray())
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalArgumentException::class.java) { repository.apply(session.snapshot, plan) }
        assertEquals(0, device.writes)
        assertEquals("new game progress", String(device.read(source).bytes))
        assertTrue(repository.history().isEmpty())
    }
    @Test fun failureRollsBackReadingFlagsAndOriginalDataTogether() {
        val device = Device()
        val (session, plan) = exported(device)
        device.failAt = 2
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalStateException::class.java) { repository.apply(session.snapshot, plan) }
        session.snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun processDeathLeavesRecoverableJournalAndUntouchedExportOriginals() {
        val device = Device()
        val (session, plan) = exported(device)
        device.dieAt = 1
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        assertThrows(Death::class.java) { repository.apply(session.snapshot, plan) }
        device.dieAt = -1
        SaveRepository(directory, device).recover(repository.pending().single())
        session.snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun everyReadingConfigurationIsCapturedEvenWhenItsFlagsAlreadyEqualZero() {
        val device = Device()
        val (session, plan) = exported(device)
        val second = session.snapshot.prefs!!.copy(path = "/data/user/0/com.test.game/shared_prefs/second.xml", bytes = """<map><int name="OpenRijTest_99" value="0"/></map>""".toByteArray())
        device.files[second.path] = second
        val snapshot = session.snapshot.copy(items = session.snapshot.items + second)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, plan)
        assertTrue(repository.originals(id).files.any { it.path == second.path && it.bytes.contentEquals(second.bytes) })
    }
}
