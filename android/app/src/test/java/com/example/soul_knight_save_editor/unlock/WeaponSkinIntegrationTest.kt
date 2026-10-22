package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeaponSkinIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = "/data/user/0/com.test.game"
    private class Memory(override val backendId: String) : DeviceStorage, SaveScanAccess {
        val files = linkedMapOf<String, SaveFile>()
        val readPaths = mutableListOf<String>()
        var attempts = 0
        var failAt = -1
        override fun stop(packageName: String, user: Int) {}
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource) = files.keys.filter {
            it.startsWith("$root/${source.directory}/") && source.filename.matches(it.substringAfterLast('/'))
        }
        override fun read(path: String) = files.getValue(path).also { readPaths += path }.let { it.copy(bytes = it.bytes.copyOf()) }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            attempts++
            if (attempts == failAt) error("injected fifth-file failure")
            files[file.path] = file.copy(bytes = bytes.copyOf())
        }
    }
    private fun snapshot(device: Memory): SaveSnapshot {
        fun file(path: String, bytes: ByteArray) = SaveFile("$root/$path", bytes, "1000:1000", "600", "u:object_r:app_data_file:s0")
        val prefs = file("shared_prefs/prefs.xml", """<map><string name="42_c0_unlock">false</string><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/></map>""".toByteArray())
        val game = file("files/game.data", UnlockEngine.xor("""{"heroUnlock":{"Knight":false},"skinLock":{"Knight":[]}}""".toByteArray()))
        val item = file("files/item_data_42_.data", ItemCodec.encode(Json.parseToJsonElement("""{"AppVersion":80700,"materials":{"material_cell":2}}""").jsonObject))
        val stat = file("files/statistic_42_.data", StatisticCodec.encode(Json.parseToJsonElement("""{"object2ObtainTime":{"weapon_007":2}}""").jsonObject))
        val skins = file("files/weapon_evolution_data_42_.data", ItemCodec.encode(Json.parseToJsonElement("""{"weapons":{},"unknown":{"keep":true}}""").jsonObject))
        return SaveSnapshot("com.test.game", 0, prefs, game, listOf(item), listOf(stat), listOf(skins)).also {
            it.files.forEach { source -> device.files[source.path] = source }
        }
    }
    private fun combined(snapshot: SaveSnapshot) = EditEngine.preview(snapshot, "42", UnlockSelection(heroes = setOf(0)),
        ItemSelection(increments = mapOf(ItemKey("materials", "material_cell") to 7)),
        WeaponSelection(increments = mapOf("weapon_007" to 8)),
        WeaponSkinSelection(unlock = setOf("weapon_007_s_1"), current = mapOf("weapon_007" to 1)))

    @Test fun fifthFileFailureRollsBackAllFeaturesForBothBackends() {
        for (backend in listOf("native-root", "shizuku-root")) {
            val device = Memory(backend)
            val snapshot = snapshot(device)
            val plan = combined(snapshot)
            assertEquals(5, plan.outputs.size)
            assertEquals(setOf("角色", "物品", "武器", "武器皮肤"), plan.sections.keys)
            val repository = SaveRepository(temp.newFolder(), device)
            device.failAt = 5
            assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
            snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
            assertTrue(repository.pending().isEmpty())
        }
    }
    @Test fun versionOrSkinFileDriftPreventsAnyWrite() {
        for (changed in listOf("item_data", "weapon_evolution_data")) {
            val device = Memory("native-root")
            val snapshot = snapshot(device)
            val plan = combined(snapshot)
            val file = snapshot.files.single { it.path.substringAfterLast('/').startsWith(changed) }
            device.files[file.path] = file.copy(bytes = "outside change".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { SaveRepository(temp.newFolder(), device).apply(snapshot, plan) }
            assertEquals(0, device.attempts)
        }
    }
    @Test fun skinOnlyOperationUsesItsOwnShardAndExistingFlags() {
        val device = Memory("native-root")
        val snapshot = snapshot(device)
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(), WeaponSelection(),
            WeaponSkinSelection(unlock = setOf("weapon_007_s_1")))
        assertEquals(setOf(snapshot.prefs!!.path, snapshot.weaponSkins.single().path), plan.outputs.keys)
        assertEquals(setOf("武器皮肤", "读取来源"), plan.sections.keys)
        val repository = SaveRepository(temp.newFolder(), device)
        val id = repository.apply(snapshot, plan)
        assertEquals(plan.outputs.keys, repository.originals(id).files.map { it.path }.toSet())
        assertEquals("0", UnlockEngine.prefs(device.read(snapshot.prefs.path).bytes).getValue("OpenRijTest_42").getAttribute("value"))
        assertArrayEquals(snapshot.game!!.bytes, device.read(snapshot.game.path).bytes)
        repository.restore(id)
        assertArrayEquals(snapshot.weaponSkins.single().bytes, device.read(snapshot.weaponSkins.single().path).bytes)
        assertEquals("0", UnlockEngine.prefs(device.read(snapshot.prefs.path).bytes).getValue("OpenNewtonJsonTest_42").getAttribute("value"))
    }
    @Test fun scannerAndPinnedRefreshIncludeSkinShardButNeverNewFiles() {
        val device = Memory("native-root")
        val source = snapshot(device)
        val skin = source.weaponSkins.single()
        device.files["${skin.path}.new"] = skin.copy(path = "${skin.path}.new")
        val scanned = SaveScanner.scan(device, "com.test.game", 0)
        assertEquals(listOf(skin.path), scanned.weaponSkins.map { it.path })
        assertArrayEquals(skin.bytes, scanned.weaponSkins.single().bytes)
        assertFalse("${skin.path}.new" in device.readPaths)
        val pinned = PinnedSaveTarget.from(scanned, "42")
        assertEquals(listOf(skin.path), pinned.weaponSkins)
        assertTrue(SaveLayout.editablePath.matches(skin.path))
        assertFalse(SaveLayout.editablePath.matches("${skin.path}.new"))
        device.readPaths.clear()
        SaveScanner.refresh(device, pinned)
        assertEquals(source.files.map { it.path }.toSet(), device.readPaths.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            SaveScanner.refresh(device, pinned.copy(weaponSkins = listOf("/data/user/0/com.other.game/files/weapon_evolution_data_42_.data")))
        }
    }
    @Test fun accountBindingNeverFallsBackToDefaultOrAnotherAccountAndRejectsDuplicates() {
        val device = Memory("native-root")
        val source = snapshot(device)
        val skin = source.weaponSkins.single()
        val another = skin.copy(path = skin.path.replace("_42_", "_99_"))
        val default = skin.copy(path = "$root/files/weapon_evolution_data.data")
        val mixed = source.copy(weaponSkins = listOf(another, default, skin))
        assertEquals(listOf("42", "99"), EditEngine.accounts(mixed))
        assertEquals(skin.path, EditEngine.weaponSkinFile(mixed, "42")!!.path)
        assertEquals(another.path, EditEngine.weaponSkinFile(mixed, "99")!!.path)
        assertNull(EditEngine.weaponSkinFile(source.copy(weaponSkins = listOf(default)), "42"))
        assertThrows(IllegalArgumentException::class.java) { EditEngine.weaponSkinInventory(source.copy(weaponSkins = listOf(default)), "42") }
        val duplicate = skin.copy(path = "$root/files/sub/weapon_evolution_data_42_.data")
        assertThrows(IllegalArgumentException::class.java) { EditEngine.weaponSkinFile(source.copy(weaponSkins = listOf(skin, duplicate)), "42") }
        val hex = "abcdef0123456789"
        assertEquals(hex, EditEngine.weaponSkinAccount(skin.path.replace("_42_", "_${hex}_")))
    }
    @Test fun skinDraftsAreSeparatePerModeAndClearedOnTargetChanges() {
        val quick = WeaponSkinSelection(unlockAll = true)
        val expert = WeaponSkinSelection(current = mapOf("weapon_007" to 0))
        val state = AssistantState(quickWeaponSkins = quick, expertWeaponSkins = expert)
        assertEquals(quick, state.weaponSkinsFor(AssistantMode.QUICK))
        assertEquals(expert, state.weaponSkinsFor(AssistantMode.EXPERT))
        assertFalse(state.clearLoaded("refresh").quickWeaponSkins.hasChanges)
        assertFalse(state.selectPackage("com.other.game").expertWeaponSkins.hasChanges)
    }
}
