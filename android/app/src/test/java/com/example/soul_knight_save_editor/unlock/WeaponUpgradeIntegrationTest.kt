package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WeaponUpgradeIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = "/data/user/0/com.test.game"
    private class Memory(override val backendId: String) : DeviceStorage {
        val files = linkedMapOf<String, SaveFile>()
        var attempts = 0
        var failAt = -1
        override fun stop(packageName: String, user: Int) {}
        override fun read(path: String) = files.getValue(path).let { it.copy(bytes = it.bytes.copyOf()) }
        override fun replace(file: SaveFile, bytes: ByteArray) {
            attempts++
            if (attempts == failAt) error("injected failure")
            files[file.path] = file.copy(bytes = bytes.copyOf())
        }
    }
    private fun snapshot(device: Memory, mirror: Boolean = true): SaveSnapshot {
        fun file(path: String, bytes: ByteArray) = SaveFile("$root/$path", bytes, "1000:1000", "600", "u:object_r:app_data_file:s0")
        val prefs = file("shared_prefs/prefs.xml", """<map><string name="42_c0_unlock">false</string><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/></map>""".toByteArray())
        val itemRoot = Json.parseToJsonElement("""{"AppVersion":80700,"materials":{"material_cell":2},"mythicWeapons":[{"id":0,"level":-1,"keep":7},{"id":999,"level":8}],"unknown":{"keep":true}}""").jsonObject
        val gameRoot = buildJsonObject {
            put("heroUnlock", buildJsonObject { put("Knight", false) })
            put("skinLock", buildJsonObject { put("Knight", JsonArray(emptyList())) })
            if (mirror) put("itemData", itemRoot)
            put("untouched", "same")
        }
        val game = file("files/game.data", UnlockEngine.xor(gameRoot.toString().toByteArray()))
        val item = file("files/item_data_42_.data", ItemCodec.encode(itemRoot))
        val stat = file("files/statistic_42_.data", StatisticCodec.encode(Json.parseToJsonElement("""{"object2ObtainTime":{"weapon_007":2}}""").jsonObject))
        val skins = file("files/weapon_evolution_data_42_.data", ItemCodec.encode(Json.parseToJsonElement("""{"weapons":{"weapon_007":{"Name":"weapon_007","Level":0,"UnlockedSkins":[],"CurrentSkinIndex":0,"keep":9}},"unknown":{"keep":true}}""").jsonObject))
        return SaveSnapshot("com.test.game", 0, prefs, game, listOf(item), listOf(stat), listOf(skins)).also {
            it.files.forEach { source -> device.files[source.path] = source }
        }
    }
    private fun combined(snapshot: SaveSnapshot) = EditEngine.preview(snapshot, "42", UnlockSelection(heroes = setOf(0)),
        ItemSelection(increments = mapOf(ItemKey("materials", "material_cell") to 7), blueprints = true),
        WeaponSelection(increments = mapOf("weapon_007" to 8)),
        WeaponSkinSelection(unlock = setOf("weapon_007_s_1"), current = mapOf("weapon_007" to 1)),
        WeaponEvolutionSelection(levels = mapOf("weapon_007" to 1)), MythicSelection(levels = mapOf(0 to 3)))

    @Test fun composingItemsMythicsSkinsEvolutionAndCharactersPreservesEveryPatch() {
        val snapshot = snapshot(Memory("native-root"))
        val plan = combined(snapshot)
        assertEquals(5, plan.outputs.size)
        assertEquals(setOf("角色", "物品", "神话武器", "武器", "武器皮肤", "武器进化"), plan.sections.keys)
        val item = ItemCodec.decode(plan.outputs.getValue(snapshot.items.single().path))
        assertEquals(9, item.getValue("materials").jsonObject.getValue("material_cell").jsonPrimitive.int)
        assertEquals(JsonPrimitive("Researched"), item.getValue("blueprints").jsonObject["blueprint_weapon_boss_void_origin"])
        val mythics = item.getValue("mythicWeapons").jsonArray
        assertEquals(3, mythics.single { it.jsonObject["id"] == JsonPrimitive(0) }.jsonObject.getValue("level").jsonPrimitive.int)
        assertEquals(JsonPrimitive(7), mythics.first().jsonObject["keep"])
        assertEquals(Json.parseToJsonElement("""{"id":999,"level":8}"""), mythics.last())
        val game = UnlockEngine.game(plan.outputs.getValue(snapshot.game!!.path))
        assertEquals(item, game["itemData"])
        assertEquals(JsonPrimitive("same"), game["untouched"])
        assertEquals(JsonPrimitive(true), game.getValue("heroUnlock").jsonObject["Knight"])
        val evolution = ItemCodec.decode(plan.outputs.getValue(snapshot.weaponSkins.single().path))
        val weapon = evolution.getValue("weapons").jsonObject.getValue("weapon_007").jsonObject
        assertEquals(JsonPrimitive(1), weapon["Level"])
        assertEquals(JsonPrimitive(1), weapon["CurrentSkinIndex"])
        assertEquals(JsonPrimitive(9), weapon["keep"])
        assertTrue(JsonPrimitive("weapon_007_s_1") in weapon.getValue("UnlockedSkins").jsonArray)
        assertEquals(Json.parseToJsonElement("""{"keep":true}"""), evolution["unknown"])
    }

    @Test fun combinedFiveFileFailureRollsBackBothBackendsAndDriftPreventsWrites() {
        for (backend in listOf("native-root", "shizuku-root")) {
            val device = Memory(backend)
            val snapshot = snapshot(device)
            val plan = combined(snapshot)
            val repository = SaveRepository(temp.newFolder(), device)
            device.failAt = 5
            assertThrows(IllegalStateException::class.java) { repository.apply(snapshot, plan) }
            snapshot.files.forEach { assertArrayEquals(it.bytes, device.read(it.path).bytes) }
            assertTrue(repository.pending().isEmpty())
            device.attempts = 0
            device.failAt = -1
            val item = snapshot.items.single()
            device.files[item.path] = item.copy(bytes = "changed".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { repository.apply(snapshot, plan) }
            assertEquals(0, device.attempts)
        }
    }

    @Test fun mythicOnlySynchronizesExistingMirrorAndRejectsMismatchedAccount() {
        val snapshot = snapshot(Memory("native-root"))
        fun plan(source: SaveSnapshot) = EditEngine.preview(source, "42", UnlockSelection(), ItemSelection(), WeaponSelection(),
            mythic = MythicSelection(unlock = setOf(0)))
        val plan = plan(snapshot)
        assertEquals(setOf(snapshot.prefs!!.path, snapshot.game!!.path, snapshot.items.single().path), plan.outputs.keys)
        val changed = ItemCodec.decode(plan.outputs.getValue(snapshot.items.single().path))
        assertEquals(changed, UnlockEngine.game(plan.outputs.getValue(snapshot.game.path))["itemData"])
        val mismatched = snapshot.copy(game = snapshot.game.copy(bytes = UnlockEngine.xor("""{"itemData":{}}""".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { plan(mismatched) }
        val withoutMirror = snapshot(Memory("native-root"), mirror = false)
        assertFalse(withoutMirror.game!!.path in plan(withoutMirror).outputs)
    }

    @Test fun newDraftsAreIndependentPerModeAndClearedWhenTargetChanges() {
        val quickEvolution = WeaponEvolutionSelection(allMax = true)
        val expertEvolution = WeaponEvolutionSelection(levels = mapOf("weapon_007" to 1))
        val quickMythic = MythicSelection(unlockAll = true)
        val expertMythic = MythicSelection(levels = mapOf(0 to 3))
        val state = AssistantState(quickWeaponEvolution = quickEvolution, expertWeaponEvolution = expertEvolution,
            quickMythic = quickMythic, expertMythic = expertMythic)
        assertEquals(quickEvolution, state.weaponEvolutionFor(AssistantMode.QUICK))
        assertEquals(expertEvolution, state.weaponEvolutionFor(AssistantMode.EXPERT))
        assertEquals(quickMythic, state.mythicFor(AssistantMode.QUICK))
        assertEquals(expertMythic, state.mythicFor(AssistantMode.EXPERT))
        for (cleared in listOf(state.clearLoaded("refresh"), state.selectPackage("com.other.game"))) {
            assertFalse(cleared.quickWeaponEvolution.hasChanges)
            assertFalse(cleared.expertWeaponEvolution.hasChanges)
            assertFalse(cleared.quickMythic.hasChanges)
            assertFalse(cleared.expertMythic.hasChanges)
        }
    }
}
