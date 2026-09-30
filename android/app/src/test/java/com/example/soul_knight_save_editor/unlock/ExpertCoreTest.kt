package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExpertCoreTest {
    private val cell = ItemKey("materials", "material_cell")
    private fun itemRoot() = buildJsonObject {
        put("AppVersion", 80600)
        put("materials", buildJsonObject { put("material_cell", 3); put("material_dead_cell", 9) })
        put("unknown", buildJsonObject { put("keep", true) })
    }
    private fun file(name: String, bytes: ByteArray) = SaveFile("/data/user/0/com.test.game/files/$name", bytes, "1000:1000", "600", "")

    @Test fun selectedItemUnlocksDoNotFillUnselectedEntriesOrResetJewelryRelations() {
        val targets = listOf(ItemKind.TAPE, ItemKind.BLUEPRINT, ItemKind.FACILITY, ItemKind.JEWELRY).map { ItemCatalog.ofKind(it).first().key }.toSet()
        val original = JsonObject(itemRoot() + mapOf("jewelryPlayerData" to JsonArray(listOf(JsonPrimitive("keep"))), "jewelryBlueprints" to JsonPrimitive("opaque")))
        val patch = ItemEngine.edit(ItemCodec.encode(original), ItemSelection(selected = targets))
        assertEquals(1, patch.root["blueprints"]!!.jsonObject.size)
        assertEquals(1, patch.root["itemUnlock"]!!.jsonArray.size)
        assertEquals(1, patch.root["jewelryData"]!!.jsonObject.size)
        assertEquals(original["jewelryPlayerData"], patch.root["jewelryPlayerData"])
        assertEquals(original["jewelryBlueprints"], patch.root["jewelryBlueprints"])
        assertEquals(JsonPrimitive(9), patch.root["materials"]!!.jsonObject["material_dead_cell"])
        assertEquals(4, patch.changes.size)
        assertTrue(ItemEngine.edit(patch.bytes, ItemSelection(selected = targets)).changes.isEmpty())
    }
    @Test fun quantitiesHaveDistinctAbsoluteAndIncrementSemantics() {
        val original = ItemCodec.encode(itemRoot())
        assertEquals(5, ItemEngine.quantity(ItemEngine.edit(original, ItemSelection(increments = mapOf(cell to 2))).root, cell))
        assertEquals(0, ItemEngine.quantity(ItemEngine.edit(original, ItemSelection(amounts = mapOf(cell to 0))).root, cell))
        assertThrows(IllegalArgumentException::class.java) { ItemEngine.edit(original, ItemSelection(amounts = mapOf(cell to 0), increments = mapOf(cell to 2))) }
        val conflict = ItemChoices(amountText = mapOf(cell to "0"), incrementText = mapOf(cell to "2"))
        assertFalse(conflict.valid)
        assertThrows(IllegalArgumentException::class.java) { conflict.selection() }
        assertFalse(ItemChoices(incrementText = mapOf(cell to "0")).hasChanges)
        assertTrue(ItemChoices(amountText = mapOf(cell to "0")).hasChanges)
        assertEquals(ItemSelection(), ItemChoices(amountText = mapOf(cell to " "), incrementText = mapOf(cell to "0")).selection())
    }
    @Test fun quickGroupStepsAreRepeatableUndoableAndAtomicOnFailure() {
        assertEquals(4, ItemDrafts.groups.size)
        val group = ItemDrafts.groups.entries.first { entries -> entries.value.any { it.key == cell } }
        val once = ItemDrafts.step(itemRoot(), ItemChoices(), group.key)
        val twice = ItemDrafts.step(itemRoot(), once, group.key)
        assertEquals(group.value.map { it.key }.toSet(), twice.actions.increments.keys)
        assertTrue(twice.actions.increments.values.all { it == 2000 })
        assertEquals(once, ItemDrafts.step(itemRoot(), twice, group.key, true))
        assertEquals(ItemChoices(), ItemDrafts.step(itemRoot(), once, group.key, true))
        val bad = JsonObject(itemRoot() + ("materials" to buildJsonObject { put("material_cell", Int.MAX_VALUE - 999) }))
        assertThrows(IllegalArgumentException::class.java) { ItemDrafts.step(bad, ItemChoices(), group.key) }
        assertEquals(1000, once.actions.increments[cell])
        assertThrows(IllegalArgumentException::class.java) { ItemDrafts.step(itemRoot(), ItemChoices(), group.key, true) }
    }
    @Test fun expertWeaponSubsetPreservesMalformedAndUnselectedCounters() {
        val original = buildJsonObject {
            put("object2ObtainTime", buildJsonObject { put("weapon_361", 1); put("weapon_900", "opaque"); put("weapon_custom", 2) })
            put("StatisticsData", JsonPrimitive("keep"))
        }
        val bytes = StatisticCodec.encode(original)
        val item = ItemCodec.encode(itemRoot())
        val patch = WeaponEngine.edit(bytes, item, WeaponSelection(increments = mapOf("weapon_361" to 8, "weapon_359" to 3, "weapon_custom" to 1)))
        val counts = patch.root["object2ObtainTime"]!!.jsonObject
        assertEquals(9, counts["weapon_361"]!!.jsonPrimitive.int)
        assertEquals(3, counts["weapon_359"]!!.jsonPrimitive.int)
        assertEquals(3, counts["weapon_custom"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive("opaque"), counts["weapon_900"])
        assertEquals(original - "object2ObtainTime", patch.root - "object2ObtainTime")
        assertThrows(IllegalArgumentException::class.java) { WeaponEngine.addEight(bytes, item) }
        assertThrows(IllegalArgumentException::class.java) { WeaponEngine.edit(bytes, item, WeaponSelection(increments = mapOf("weapon_unknown_absent" to 1))) }
        assertThrows(IllegalArgumentException::class.java) { WeaponEngine.edit(bytes, item, WeaponSelection(increments = mapOf("weapon_361" to -1))) }
        assertThrows(IllegalArgumentException::class.java) { WeaponEngine.edit(bytes, item, WeaponSelection(increments = mapOf("weapon_361" to Int.MAX_VALUE))) }
        assertArrayEquals(bytes, WeaponEngine.edit(bytes, item, WeaponSelection(increments = mapOf("weapon_361" to 0))).bytes)
        assertNotNull(WeaponEngine.states(WeaponEngine.inspect(bytes, item)).single { it.id == "weapon_900" }.error)
    }
    @Test fun expertRoleProgressAndPetsChangeOnlySelectedExistingKeys() {
        val original = UnlockEngine.xor("""{"heroUnlock":{"Knight":false,"Robot":true},"skinLock":{"Knight":[],"Robot":[]},"heroLevel":{"Knight":3,"Robot":9},"heroSkillUnlock":{"Knight":[{"Key":0,"Value":false},{"Key":1,"Value":false}],"Robot":[{"Key":0,"Value":false}]},"keep":1}""".toByteArray())
        val prefs = """<map><int name="42_c0_unlock" value="0"/><int name="42_c1_unlock" value="1"/><int name="42_c0_level" value="3"/><int name="42_c1_level" value="9"/><int name="42_c_Knight_skill_0_unlock" value="0"/><int name="42_c_Knight_skill_1_unlock" value="0"/><string name="42_p0_unlock">false</string><string name="42_p1_unlock">false</string><string name="42_p17_unlock">false</string></map>""".toByteArray()
        val patch = UnlockEngine.unlock(original, prefs, "42", UnlockSelection(levelHeroes = setOf(0), skillIds = setOf(SkillId(0, 1))))
        val root = UnlockEngine.game(patch.game!!)
        assertEquals(7, root["heroLevel"]!!.jsonObject["Knight"]!!.jsonPrimitive.int)
        assertEquals(9, root["heroLevel"]!!.jsonObject["Robot"]!!.jsonPrimitive.int)
        val skills = root["heroSkillUnlock"]!!.jsonObject["Knight"]!!.jsonArray
        assertFalse(skills[0].jsonObject["Value"]!!.jsonPrimitive.boolean)
        assertTrue(skills[1].jsonObject["Value"]!!.jsonPrimitive.boolean)
        assertEquals(UnlockEngine.game(original)["heroSkillUnlock"]!!.jsonObject["Robot"], root["heroSkillUnlock"]!!.jsonObject["Robot"])
        assertThrows(Exception::class.java) { UnlockEngine.unlock(original, prefs, "42", UnlockSelection(skillIds = setOf(SkillId(0, 99)))) }
        assertTrue(UnlockEngine.unlock(patch.game, patch.prefs, "42", UnlockSelection(levelHeroes = setOf(0), skillIds = setOf(SkillId(0, 1)))).changes.isEmpty())
        val pet = PetEngine.unlock(null, prefs, "42", setOf("Cat"))
        val nodes = UnlockEngine.prefs(pet.prefs)
        assertEquals("true", nodes.getValue("42_p0_unlock").textContent)
        assertEquals("false", nodes.getValue("42_p1_unlock").textContent)
        assertEquals("false", nodes.getValue("42_p17_unlock").textContent)
        assertThrows(IllegalArgumentException::class.java) { PetEngine.unlock(null, prefs, "42", setOf("Hide")) }
    }
    @Test fun modesKeepIndependentDraftsAndPackageChangeClearsBoth() {
        val quick = ItemChoices(actions = ItemSelection(tapes = true))
        val expert = ItemChoices(amountText = mapOf(cell to "0"))
        val state = AssistantState(itemChoices = quick, expertItems = expert, choices = ModeChoices(quickWeapons = true),
            expertWeapons = WeaponChoices(mapOf("weapon_361" to "2")))
        assertEquals(quick, state.itemsFor(AssistantMode.QUICK))
        assertEquals(expert, state.itemsFor(AssistantMode.EXPERT))
        assertTrue(state.weaponsFor(AssistantMode.QUICK).allPlusEight)
        assertEquals(mapOf("weapon_361" to 2), state.weaponsFor(AssistantMode.EXPERT).increments)
        val next = state.selectTab(WorkspaceTabs.weapons).selectPackage("com.other.game")
        assertEquals(ItemChoices(), next.expertItems)
        assertEquals(WeaponChoices(), next.expertWeapons)
        assertEquals(ItemChoices(), next.itemChoices)
    }
    @Test fun expertMixedPlanScopesAccountAndProvidesGroupedSummary() {
        val item = file("item_data_42_.data", ItemCodec.encode(itemRoot()))
        val other = file("item_data_99_.data", ItemCodec.encode(itemRoot()))
        val stat = file("statistic_42_.data", StatisticCodec.encode(buildJsonObject { put("object2ObtainTime", buildJsonObject { put("weapon_361", 1) }) }))
        val save = SaveSnapshot("com.test.game", 0, null, null, listOf(item, other), listOf(stat))
        val report = SaveInspector.inspect(save, "42")
        assertTrue(report.characters.isFailure)
        assertTrue(report.items.isSuccess)
        assertTrue(report.weapons.isSuccess)
        val plan = EditEngine.preview(save, "42", UnlockSelection(), ItemSelection(amounts = mapOf(cell to 5)), WeaponSelection(increments = mapOf("weapon_361" to 2)))
        assertEquals(setOf(item.path, stat.path), plan.outputs.keys)
        assertEquals(setOf("物品", "武器"), plan.sections.keys)
        assertEquals(2, plan.changes.size)
        assertFalse(other.path in plan.outputs)
    }
}
