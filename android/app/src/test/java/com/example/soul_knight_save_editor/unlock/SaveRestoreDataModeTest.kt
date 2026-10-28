package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SaveRestoreDataModeTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        val written = mutableListOf<String>()
        var failAt = -1
        private var attempts = 0
        override fun stop(packageName: String, user: Int) {}
        override fun read(path: String) = files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            attempts++
            if (attempts == failAt) error("injected restore failure")
            written += file.path
            files[file.path] = file.copy(bytes = bytes.copyOf())
        }
    }
    private fun file(relative: String, text: String) = SaveFile("/data/user/0/com.test.game/$relative",
        text.toByteArray(), "10100:10100", "600", "")
    private fun xml(rij: Int, newton: Int, label: String) = """<map>
        <string name="AccountSaveSettingsIds">{"42":"700"}</string>
        <int name="OpenRijTest_700" value="$rij"/><int name="OpenNewtonJsonTest_700" value="$newton"/>
        <int name="OpenRijTest_900" value="1"/><string name="custom">$label</string>
    </map>""".trimIndent()
    private fun payload(vararg files: SaveFile) = BackupPayload("com.test.game", 0, files.toList())

    @Test fun modernRestoreKeepsEveryBackedUpFlagValueAndRestoresBothFormats() {
        val prefs = file("shared_prefs/game.xml", xml(1, 0, "backup"))
        val old = file("files/item_data_42_.data", "old backup")
        val modern = file("files/item_data_42_.data.new", "modern backup")
        val sources = listOf(prefs, old, modern)
        val device = Device().apply {
            sources.forEach { files[it.path] = it.copy(bytes = "live".toByteArray()) }
        }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(*sources.toTypedArray()), dataMode = SaveDataMode.MODERN)
        sources.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertEquals(sources.map { it.path }.toSet(), device.written.toSet())
        assertTrue(repository.pending().isEmpty())
    }

    @Test fun modernShardOnlyRestoreLeavesCurrentFallbackXmlExactlyUntouched() {
        val prefs = file("shared_prefs/game.xml", xml(0, 1, "current"))
        val item = file("files/item_data_42_.data.new", "backup")
        val device = Device().apply {
            files[prefs.path] = prefs
            files[item.path] = item.copy(bytes = "live".toByteArray())
        }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(item), prefs.path, SaveDataMode.MODERN)
        assertArrayEquals(prefs.bytes, device.read(prefs.path).bytes)
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
        assertEquals(listOf(item.path), device.written)
    }

    @Test fun modernTransactionRestorePropagatesItsModeToArchivedOriginals() {
        val prefs = file("shared_prefs/game.xml", xml(1, 1, "backup"))
        val item = file("files/item_data_42_.data.new", "backup")
        val device = Device().apply { files[prefs.path] = prefs; files[item.path] = item }
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(SaveSnapshot("com.test.game", 0, prefs, null, listOf(item)),
            SavePlan(mapOf(item.path to "edited".toByteArray()), emptyList()))
        device.files[prefs.path] = prefs.copy(bytes = xml(0, 0, "live").toByteArray())
        repository.restore(id, SaveDataMode.MODERN)
        assertArrayEquals(prefs.bytes, device.read(prefs.path).bytes)
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
    }

    @Test fun failedModernRestoreRollsBackToLiveXmlWithoutChangingItsFlags() {
        val backupPrefs = file("shared_prefs/game.xml", xml(1, 1, "backup"))
        val item = file("files/item_data_42_.data.new", "backup")
        val live = listOf(backupPrefs.copy(bytes = xml(0, 1, "live").toByteArray()), item.copy(bytes = "live".toByteArray()))
        val device = Device().apply { live.forEach { files[it.path] = it }; failAt = 2 }
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalStateException::class.java) {
            repository.restore(payload(backupPrefs, item), dataMode = SaveDataMode.MODERN)
        }
        live.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
}
