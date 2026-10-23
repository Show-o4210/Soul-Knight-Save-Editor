package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RelockSourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private val pkg = "com.test.game"
    private val path = "/data/user/0/$pkg/shared_prefs/game.xml"
    private fun xml(role: String = "true", skin: Int = 1, skill: Int = 1, account: String = "42") =
        """<map><string name="${account}_c0_unlock">true</string><string name="${account}_c1_unlock">$role</string><int name="${account}_c1_skin1" value="$skin"/><int name="${account}_c_Ranger_skill_1_unlock" value="$skill"/></map>""".toByteArray()
    private fun file(bytes: ByteArray = xml()) = SaveFile(path, bytes, "10001:10001", "660", "")
    private fun snapshot() = SaveSnapshot(pkg, 0, file(), null)
    private fun payload(bytes: ByteArray, packageName: String = pkg, user: Int = 0, prefPath: String = path) =
        BackupPayload(packageName, user, listOf(file(bytes).copy(path = prefPath)), "pre-change")
    private fun values(result: RelockSourceResult) = UnlockEngine.prefs(result.originals.single().prefs).mapValues { (_, node) ->
        if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
    }

    @Test fun newestKnownLockedValueWinsPerKeyAndUnlockedBackupsDoNotEraseEvidence() {
        val result = RelockSources.collect(snapshot(), "42", listOf(
            { payload(xml()) },
            { payload(xml(role = "false", skin = -13)) },
            { payload(xml(role = "false", skin = 15000, skill = 0)) }))
        assertEquals("false", values(result)["42_c1_unlock"])
        assertEquals("-13", values(result)["42_c1_skin1"])
        assertEquals("0", values(result)["42_c_Ranger_skill_1_unlock"])
        assertEquals(2, result.sourceCount)
        assertEquals(0, result.skippedBackups)
        assertEquals(1, result.originals.size)
    }

    @Test fun sourceRequiresSamePackageUserAccountAndPrefsPathButAllowsUserZeroAlias() {
        val old = xml(role = "false", skin = -6, skill = 0)
        val result = RelockSources.collect(snapshot(), "42", listOf(
            { payload(old, "com.other.game", prefPath = path.replace(pkg, "com.other.game")) },
            { payload(old, user = 10, prefPath = path.replace("/user/0/", "/user/10/")) },
            { payload(xml(role = "false", skin = 2000, skill = 0, account = "43")) },
            { payload(old, prefPath = path.replace("game.xml", "other.xml")) },
            { payload(old, prefPath = path.replace("/data/user/0/", "/data/data/")) }))
        assertEquals(1, result.sourceCount)
        assertEquals("-6", values(result)["42_c1_skin1"])
        assertTrue(values(result).keys.all { it.startsWith("42_") })
    }

    @Test fun corruptedOrUnknownNodesAreSkippedWithoutDiscardingOtherValidOriginals() {
        val unknown = """<map><string name="42_c1_unlock">maybe</string><string name="42_c1_skin1">-6</string></map>""".toByteArray()
        val result = RelockSources.collect(snapshot(), "42", listOf(
            { throw IllegalStateException("corrupt archive") }, { payload(unknown) },
            { payload(xml(skin = 233)) }))
        assertEquals(1, result.skippedBackups)
        assertEquals("233", values(result)["42_c1_skin1"])
        assertFalse("42_c1_unlock" in values(result))
    }

    @Test fun transactionOriginalHashIsVerifiedAndUnrelatedShardPayloadIsNotLoaded() {
        class Device : DeviceStorage {
            val files = linkedMapOf<String, SaveFile>()
            override fun stop(packageName: String, user: Int) {}
            override fun read(path: String) = files.getValue(path)
            override fun replace(file: SaveFile, bytes: ByteArray) { files[file.path] = file.copy(bytes = bytes) }
        }
        val device = Device()
        val before = file(xml(role = "false", skin = -19, skill = 0))
        val game = before.copy(path = "/data/user/0/$pkg/files/game.data", bytes = byteArrayOf(1, 2, 3))
        val snapshot = SaveSnapshot(pkg, 0, before, game)
        snapshot.files.forEach { device.files[it.path] = it }
        val directory = temp.newFolder()
        val repository = SaveRepository(directory, device)
        val id = repository.apply(snapshot, SavePlan(mapOf(path to xml(), game.path to byteArrayOf(4, 5, 6)), listOf("unlock")))
        assertTrue(File(directory, "$id/1.before").delete())
        val selected = requireNotNull(repository.relockPrefs(id, snapshot))
        assertEquals(1, selected.files.size)
        assertArrayEquals(before.bytes, selected.files.single().bytes)
        val broken = File(directory, "1-00000000-0000-0000-0000-000000000000").apply { check(mkdir()) }
        File(broken, "manifest.json").writeText("corrupt")
        File(broken, "state").writeText("\ncomplete\n")
        val sources = RelockSources.load(snapshot.copy(prefs = device.read(path)), "42", repository, emptyList())
        assertEquals(1, sources.skippedBackups)
        assertEquals("-19", values(sources)["42_c1_skin1"])
        File(directory, "$id/0.before").writeBytes("corrupt".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { repository.relockPrefs(id, snapshot) }
    }
}
