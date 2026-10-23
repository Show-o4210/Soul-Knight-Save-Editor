package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveRestoreTest {
    @get:Rule val temp = TemporaryFolder()
    private class Death : Error()
    private class Device : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        var attempts = 0
        var failAt = -1
        var dieAt = -1
        override fun stop(packageName: String, user: Int) {}
        override fun read(path: String) = files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            attempts++
            if (attempts == failAt) error("write failed")
            files[file.path] = file.copy(bytes = bytes.copyOf())
            if (attempts == dieAt) throw Death()
        }
    }
    private val root = "/data/user/0/com.test.game"
    private fun file(relative: String, text: String) = SaveFile("$root/$relative", text.toByteArray(), "10100:10100", "600", "u:object_r:app_data_file:s0")
    private fun xml(value: Int, suffix: String = "_42") = """<map><int name="OpenRijTest$suffix" value="$value"/><int name="OpenNewtonJsonTest$suffix" value="$value"/><string name="unchanged">keep me</string></map>"""
    private fun payload(vararg files: SaveFile) = BackupPayload("com.test.game", 0, files.toList())

    @Test fun importedOpaqueShardsRestoreExactBytesAndXmlFlagsBecomeZeroWithoutChangingArchive() {
        val original = listOf(file("shared_prefs/game.xml", xml(1)), file("files/account/progress.data", "opaque-before"))
        val archive = BackupArchive.create(temp.newFolder(), payload(*original.toTypedArray()))
        val hash = UnlockEngine.sha(archive.file.readBytes())
        val device = Device()
        original.forEach { device.files[it.path] = it.copy(bytes = "new progress".toByteArray(), owner = "20200:20200", context = "new-label") }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(BackupArchive.read(archive.file))
        assertEquals(xml(0), String(device.read(original[0].path).bytes))
        assertArrayEquals(original[1].bytes, device.read(original[1].path).bytes)
        assertEquals("20200:20200", device.read(original[0].path).owner)
        assertEquals("new-label", device.read(original[0].path).context)
        assertEquals(hash, UnlockEngine.sha(archive.file.readBytes()))
        assertEquals(xml(1), String(BackupArchive.read(archive.file).files[0].bytes))
        assertTrue(repository.originals(repository.completed().single()).files.all { String(it.bytes) == "new progress" })
    }
    @Test fun defaultAndHexAccountsAreResetMissingKeysAreNotCreated() {
        for (suffix in listOf("", "_abcdef0123456789", "_42")) {
            val source = file("shared_prefs/game.xml", xml(1, suffix))
            val device = Device().apply { files[source.path] = source }
            SaveRepository(temp.newFolder(), device).restore(payload(source))
            assertEquals(xml(0, suffix), String(device.read(source.path).bytes))
        }
        val source = file("shared_prefs/game.xml", "<map><string name=\"keep\">1</string></map>")
        val device = Device().apply { files[source.path] = source }
        SaveRepository(temp.newFolder(), device).restore(payload(source))
        assertArrayEquals(source.bytes, device.read(source.path).bytes)
        assertEquals(0, device.attempts)
    }
    @Test fun unknownFlagsRejectRestoreBeforeAnyReplacement() {
        val source = file("shared_prefs/game.xml", xml(9))
        val device = Device().apply { files[source.path] = source }
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalArgumentException::class.java) { repository.restore(payload(source)) }
        assertEquals(0, device.attempts)
        assertTrue(repository.history().isEmpty())
    }
    @Test fun failureRestoresTheProgressPresentBeforeManualRestoreIncludingItsOriginalFlagValue() {
        val prefs = file("shared_prefs/game.xml", xml(1))
        val data = file("files/local.data", "old")
        val live = listOf(prefs.copy(bytes = xml(1).replace("keep me", "new progress").toByteArray()), data.copy(bytes = "new".toByteArray()))
        val device = Device().apply { live.forEach { files[it.path] = it }; failAt = 2 }
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(IllegalStateException::class.java) { repository.restore(payload(prefs, data)) }
        live.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun processDeathDuringManualRestoreCanRollBackOnRestart() {
        val prefs = file("shared_prefs/game.xml", xml(1))
        val data = file("files/local.data", "old")
        val live = listOf(prefs.copy(bytes = xml(1).replace("keep me", "new progress").toByteArray()), data.copy(bytes = "new".toByteArray()))
        val device = Device().apply { live.forEach { files[it.path] = it }; dieAt = 1 }
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        assertThrows(Death::class.java) { repository.restore(payload(prefs, data)) }
        val pending = repository.pending().single()
        device.dieAt = -1
        SaveRepository(directory, device).recover(pending)
        live.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun unchangedPreferencesAreIncludedInNewAutomaticBackupsAndResetOnRestore() {
        val prefs = file("shared_prefs/game.xml", xml(0))
        val item = file("files/item_data_42_.data", "old")
        val device = Device().apply { files[prefs.path] = prefs; files[item.path] = item }
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(SaveSnapshot("com.test.game", 0, prefs, null, listOf(item)), SavePlan(mapOf(item.path to "edited".toByteArray()), emptyList()))
        assertEquals(setOf(prefs.path, item.path), repository.originals(id).files.map { it.path }.toSet())
        device.files[prefs.path] = prefs.copy(bytes = xml(1).toByteArray())
        repository.restore(id)
        assertEquals(xml(0), String(device.read(prefs.path).bytes))
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
    }
    @Test fun legacyShardOnlyBackupChangesOnlyItsOwnAccountInCurrentFallbackXml() {
        val prefs = file("shared_prefs/game.xml", xml(1).replace("</map>", "<int name=\"OpenRijTest_99\" value=\"1\"/></map>"))
        val item = file("files/item_data_42_.data", "old")
        val device = Device().apply { files[prefs.path] = prefs; files[item.path] = item.copy(bytes = "new".toByteArray()) }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(item), prefs.path)
        val nodes = UnlockEngine.prefs(device.read(prefs.path).bytes)
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_99").getAttribute("value"))
        assertTrue(String(device.read(prefs.path).bytes).contains("keep me"))
    }
    @Test fun completeXmlRestoreResetsRawScopesWithoutTreatingChillyUidsAsFileAccounts() {
        val source = file("shared_prefs/game.xml", """<map>
            <string name="AccountSaveSettingsIds">%7B%2242%22%3A%22700%22%7D</string>
            <int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/>
            <int name="OpenRijTest_700" value="1"/><int name="OpenNewtonJsonTest_700" value="1"/>
            <int name="OpenRijTest_900" value="1"/><int name="OpenNewtonJsonTest_900" value="1"/>
            <int name="OpenRijTest" value="1"/><string name="unchanged">keep me</string>
        </map>""".trimIndent())
        val device = Device().apply { files[source.path] = source }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(source))
        val nodes = UnlockEngine.prefs(device.read(source.path).bytes)
        for (scope in listOf("42", "700", "900")) for (flag in listOf("OpenRijTest", "OpenNewtonJsonTest"))
            assertEquals("0", nodes.getValue("${flag}_$scope").getAttribute("value"))
        assertEquals("0", nodes.getValue("OpenRijTest").getAttribute("value"))
        assertEquals("keep me", nodes.getValue("unchanged").textContent)
        assertEquals("%7B%2242%22%3A%22700%22%7D", nodes.getValue("AccountSaveSettingsIds").textContent)
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun newShardOnlyRestoreBindsCurrentFallbackXmlToItsMappedScope() {
        val prefs = file("shared_prefs/game.xml", """<map>
            <string name="AccountSaveSettingsIds">%7B%2242%22%3A%22700%22%2C%2299%22%3A%22900%22%7D</string>
            <int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/>
            <int name="OpenRijTest_700" value="1"/><int name="OpenNewtonJsonTest_700" value="1"/>
            <int name="OpenRijTest_900" value="1"/><int name="OpenNewtonJsonTest_900" value="1"/>
        </map>""".trimIndent())
        val item = file("files/item_data_42_.data.new", "before")
        val device = Device().apply { files[prefs.path] = prefs; files[item.path] = item.copy(bytes = "after".toByteArray()) }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(item), prefs.path)
        val nodes = UnlockEngine.prefs(device.read(prefs.path).bytes)
        for (flag in listOf("OpenRijTest", "OpenNewtonJsonTest")) {
            assertEquals("0", nodes.getValue("${flag}_700").getAttribute("value"))
            assertEquals("1", nodes.getValue("${flag}_42").getAttribute("value"))
            assertEquals("1", nodes.getValue("${flag}_900").getAttribute("value"))
        }
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun unresolvedFallbackBindingNeverClearsAUniqueForeignScope() {
        val prefs = file("shared_prefs/game.xml", """<map>
            <string name="AccountSaveSettingsIds">%7B%2299%22%3A%22700%22%7D</string>
            <string name="last_chilly_uid">700</string><int name="OpenRijTest_700" value="1"/>
        </map>""".trimIndent())
        val item = file("files/item_data_42_.data", "before")
        val device = Device().apply { files[prefs.path] = prefs; files[item.path] = item.copy(bytes = "after".toByteArray()) }
        val repository = SaveRepository(temp.newFolder(), device)
        repository.restore(payload(item), prefs.path)
        assertArrayEquals(prefs.bytes, device.read(prefs.path).bytes)
        assertArrayEquals(item.bytes, device.read(item.path).bytes)
        assertTrue(repository.pending().isEmpty())
    }
    @Test fun missingFileAndForeignPathsRefuseBeforeWritingAnyFile() {
        val source = file("files/local.data", "old")
        val device = Device()
        val repository = SaveRepository(temp.newFolder(), device)
        assertThrows(NoSuchElementException::class.java) { repository.restore(payload(source)) }
        assertThrows(IllegalStateException::class.java) { repository.restore(payload(source.copy(path = source.path.replace("com.test.game", "com.other.game")))) }
        assertEquals(0, device.attempts)
        assertTrue(repository.history().isEmpty())
    }
    @Test fun pruningCannotDeletePendingTransactionsAndNeverDeletesLiveFiles() {
        val source = file("files/local.data", "old")
        val device = Device().apply { files[source.path] = source; dieAt = 1 }
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        assertThrows(Death::class.java) { repository.restore(payload(source.copy(bytes = "restore".toByteArray()))) }
        val pending = repository.pending().single()
        assertTrue(repository.history().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.delete(pending) }
        device.dieAt = -1
        repository.recover(pending)
        repository.delete(pending)
        assertFalse(File(directory, pending).exists())
        assertArrayEquals(source.bytes, device.read(source.path).bytes)
    }
}
