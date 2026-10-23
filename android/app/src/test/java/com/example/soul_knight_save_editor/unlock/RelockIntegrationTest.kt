package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RelockIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private class Device : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        override fun stop(packageName: String, user: Int) {}
        override fun read(path: String) = files.getValue(path)
        override fun replace(file: SaveFile, bytes: ByteArray) { files[file.path] = file.copy(bytes = bytes) }
    }

    @Test fun realBili87UnlockThenRelockUsesHashedTransactionOriginalsAndCanRestoreTheRelock() {
        val directory = System.getProperty("relock.baseline", "").orEmpty().takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("Optional private Bilibili reference not supplied", directory?.isDirectory == true)
        val gameBytes = File(directory!!, "game.data").readBytes()
        val prefsBytes = File(directory, "playerprefs.xml").readBytes()
        val gameHash = UnlockEngine.sha(gameBytes)
        val prefsHash = UnlockEngine.sha(prefsBytes)
        val account = UnlockEngine.accounts(prefsBytes).single()
        val catalog = UnlockEngine.catalog(gameBytes, prefsBytes, account)
        val pkg = "com.knight.union.bili"
        val prefs = SaveFile("/data/user/0/$pkg/shared_prefs/$pkg.v2.playerprefs.xml", prefsBytes, "10052:10052", "660", "")
        val game = prefs.copy(path = "/data/user/0/$pkg/files/game.data", bytes = gameBytes)
        val snapshot = SaveSnapshot(pkg, 0, prefs, game)
        val device = Device().apply { snapshot.files.forEach { files[it.path] = it } }
        val repository = SaveRepository(temp.newFolder(), device)
        val unlock = UnlockSelection(heroes = catalog.heroes.map { it.index }.toSet(),
            skins = catalog.heroes.flatMap { h -> h.skins.keys.map { SkinId(h.index, it) } }.toSet(), skills = true)
        repository.apply(snapshot, EditEngine.preview(snapshot, account, unlock, ItemSelection()))
        val unlocked = snapshot.copy(prefs = device.read(prefs.path), game = device.read(game.path))
        val originals = RelockSources.load(unlocked, account, repository, emptyList())
        assertTrue(originals.sourceCount > 0)
        // Unknown package version disables the bundled Bilibili reference: this must use originals.
        val scope = RelockScope(pkg, 0)
        val inventory = RelockEngine.inspect(unlocked.game!!.bytes, unlocked.prefs!!.bytes, account, scope, originals.originals)
        assertEquals(41, inventory.heroes.size)
        assertEquals(749, inventory.skins.size)
        assertEquals(62, inventory.skills.size)
        val selection = UnlockSelection(heroes = inventory.heroes, skins = inventory.skins, skillIds = inventory.skills, relock = true)
        val plan = EditEngine.preview(unlocked, account, selection, ItemSelection(), WeaponSelection(),
            relockScope = scope, relockOriginals = originals.originals)
        val id = repository.apply(unlocked, plan)
        val checked = UnlockEngine.game(device.read(game.path).bytes)
        val before = UnlockEngine.game(gameBytes)
        for (field in listOf("heroUnlock", "skinLock", "heroSkillUnlock", "heroLevel", "heroSkill", "currentSkinDatas"))
            assertTrue("Round-trip changed $field", checked[field] == before[field])
        assertTrue("Unrelated game fields changed", checked - setOf("heroUnlock", "skinLock", "heroSkillUnlock") == before - setOf("heroUnlock", "skinLock", "heroSkillUnlock"))
        val afterCatalog = UnlockEngine.catalog(device.read(game.path).bytes, device.read(prefs.path).bytes, account)
        assertTrue(afterCatalog.heroes == catalog.heroes)
        assertEquals(1, afterCatalog.heroes.single { it.index == 23 }.skins[3])
        assertTrue(repository.pending().isEmpty())
        repository.restore(id)
        assertArrayEquals(unlocked.game.bytes, device.read(game.path).bytes)
        val restoredNodes = UnlockEngine.prefs(device.read(prefs.path).bytes)
        val flag = Regex("^(?:OpenRijTest|OpenNewtonJsonTest)(?:_${SaveAccountId.TOKEN_PATTERN})?$")
        UnlockEngine.prefs(unlocked.prefs.bytes).forEach { (name, node) ->
            val actual = restoredNodes.getValue(name)
            assertEquals(node.tagName, actual.tagName)
            val expected = if (flag.matches(name)) "0" else if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
            val value = if (actual.hasAttribute("value")) actual.getAttribute("value") else actual.textContent
            assertTrue("Restore changed an unrelated XML value", expected == value)
        }
        assertEquals(gameHash, UnlockEngine.sha(File(directory, "game.data").readBytes()))
        assertEquals(prefsHash, UnlockEngine.sha(File(directory, "playerprefs.xml").readBytes()))
    }
}
