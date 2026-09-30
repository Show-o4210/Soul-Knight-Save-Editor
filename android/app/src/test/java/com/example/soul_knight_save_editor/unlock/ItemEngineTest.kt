package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ItemEngineTest {
    private fun root(extra: String = "") = Json.parseToJsonElement("""{"AppVersion":80600,"untouched":{"flag":true}$extra}""").jsonObject
    private fun edit(root: JsonObject, selection: ItemSelection) = ItemEngine.edit(ItemCodec.encode(root), selection)
    private val cell = ItemKey("materials", "material_cell")
    private val fish = ItemKey("materials", "material_fish")

    @Test fun allowlistMatchesConfirmedScope() {
        assertEquals(573, ItemCatalog.entries.size)
        assertEquals(ItemCatalog.entries.size, ItemCatalog.byKey.size)
        assertEquals(ItemKind.QUANTITY, ItemCatalog.byKey.getValue(cell).kind)
        assertEquals(6, ItemCatalog.entries.count { it.key.id.startsWith("material_generic_weapon_fragment_") })
        val excluded = listOf("material_dead_cell", "material_fusion_fragment", "material_ticket_bossrush", "material_c17_book_1", "material_c21_book_2", "material_tape_level_2g.")
        excluded.forEach { assertFalse(it, ItemCatalog.byKey.containsKey(ItemKey("materials", it))) }
        assertEquals(setOf(fish, ItemKey("materials", "material_ticket"), ItemKey("tokenTickets", "token_hero")), ItemCatalog.ofKind(ItemKind.AMOUNT).map { it.key }.toSet())
        assertTrue(ItemCatalog.entries.all { it.key.field in setOf("materials", "seeds", "tokenTickets", "blueprints", "itemUnlock", "jewelryData") })
    }
    @Test fun zeroDefaultAndRepeatedAdditionHaveNoInventedCap() {
        val start = root(",\"materials\":{\"material_cell\":99999,\"excluded\":4}")
        val changed = edit(start, ItemSelection(increments = mapOf(cell to 2000))).root
        assertEquals(101999, ItemEngine.quantity(changed, cell))
        assertEquals(JsonPrimitive(4), changed["materials"]!!.jsonObject["excluded"])
        val seed = ItemCatalog.ofKind(ItemKind.QUANTITY).first { it.key.field == "seeds" }.key
        assertEquals(1000, ItemEngine.quantity(edit(start, ItemSelection(increments = mapOf(seed to 1000))).root, seed))
        assertEquals(start["untouched"], changed["untouched"])
    }
    @Test fun tapesAreBinaryAndDoNotTouchExcludedMaterials() {
        val tape = ItemCatalog.ofKind(ItemKind.TAPE).first().key
        val start = root(",\"materials\":{\"material_dead_cell\":5,\"material_fusion_fragment\":1,\"${tape.id}\":0}")
        val changed = edit(start, ItemSelection(tapes = true))
        assertEquals(55, changed.changes.size)
        ItemCatalog.ofKind(ItemKind.TAPE).forEach { assertEquals(1, ItemEngine.quantity(changed.root, it.key)) }
        assertEquals(JsonPrimitive(5), changed.root["materials"]!!.jsonObject["material_dead_cell"])
        assertEquals(JsonPrimitive(1), changed.root["materials"]!!.jsonObject["material_fusion_fragment"])
        assertArrayEquals(changed.bytes, ItemEngine.edit(changed.bytes, ItemSelection(tapes = true)).bytes)
        assertEquals(1, ItemEngine.quantity(edit(root(",\"materials\":{\"${tape.id}\":9999}"), ItemSelection(tapes = true)).root, tape))
    }
    @Test fun absoluteInputsSupportBothTrialTicketsAndZero() {
        val selection = ItemSelection(amounts = ItemCatalog.ofKind(ItemKind.AMOUNT).associate { it.key to 0 })
        val changed = edit(root(), selection)
        assertEquals(3, changed.changes.size)
        selection.amounts.keys.forEach { assertEquals(JsonPrimitive(0), changed.root[it.field]!!.jsonObject[it.id]) }
        assertTrue(ItemChoices().valid)
        assertFalse(ItemChoices().hasChanges)
        assertEquals(0, ItemChoices(amountText = mapOf(fish to "0")).selection().amounts[fish])
        for (text in listOf("-1", "1.5", "1e3", "2147483648", "one", " 4")) {
            assertFalse(text, ItemChoices(amountText = mapOf(fish to text)).valid)
        }
    }
    @Test fun invalidOrOutOfRangeQuantityDoesNotProducePatch() {
        for (value in listOf("-1", "1.5", "\"1\"", "null", "2147483647")) {
            assertThrows(IllegalArgumentException::class.java) { edit(root(",\"materials\":{\"material_cell\":$value}"), ItemSelection(increments = mapOf(cell to 1000))) }
        }
        assertEquals(1, ItemEngine.quantity(edit(root(), ItemSelection(increments = mapOf(cell to 1))).root, cell))
        assertEquals(50, ItemEngine.quantity(edit(root(), ItemSelection(amounts = mapOf(cell to 50))).root, cell))
        assertThrows(IllegalArgumentException::class.java) { edit(root(), ItemSelection(increments = mapOf(ItemKey("materials", "material_dead_cell") to 1000))) }
    }
    @Test fun allKnownBlueprintsBecomeResearchedUnknownEntriesRemain() {
        val id = ItemCatalog.ofKind(ItemKind.BLUEPRINT).first().key.id
        val start = root(",\"blueprints\":{\"$id\":\"Got\",\"future\":\"NewStatus\"}")
        val changed = edit(start, ItemSelection(blueprints = true))
        val entries = changed.root["blueprints"]!!.jsonObject
        assertEquals(264, entries.size)
        ItemCatalog.ofKind(ItemKind.BLUEPRINT).forEach { assertEquals(JsonPrimitive("Researched"), entries[it.key.id]) }
        assertEquals(JsonPrimitive("NewStatus"), entries["future"])
        assertTrue(ItemEngine.edit(changed.bytes, ItemSelection(blueprints = true)).changes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { edit(root(",\"blueprints\":{\"$id\":\"Unknown\"}"), ItemSelection(blueprints = true)) }
    }
    @Test fun facilitiesAppendWithoutReplacingExistingRecords() {
        val start = root(",\"itemUnlock\":[\"Motorcycle\",\"future\",\"future\"]")
        val changed = edit(start, ItemSelection(facilities = true))
        assertEquals(5, changed.changes.size)
        assertEquals(8, changed.root["itemUnlock"]!!.jsonArray.size)
        assertEquals(start["itemUnlock"]!!.jsonArray, JsonArray(changed.root["itemUnlock"]!!.jsonArray.take(3)))
        assertTrue(ItemEngine.edit(changed.bytes, ItemSelection(facilities = true)).changes.isEmpty())
    }
    @Test fun jewelryPreservesEquippedBlueprintRuneAndUnknownRecords() {
        val id = ItemCatalog.ofKind(ItemKind.JEWELRY).first().key.id
        val start = root(",\"jewelryData\":{\"$id\":{\"item\":\"$id\",\"durability\":52,\"rune1\":\"existing\",\"rune2\":\"None\",\"extra\":true},\"future\":{\"keep\":7}},\"jewelryPlayerData\":[{\"hero\":\"$id\"}],\"jewelryBlueprints\":[\"known\"]")
        val changed = edit(start, ItemSelection(jewelry = true)).root
        for (field in listOf("jewelryPlayerData", "jewelryBlueprints", "untouched")) assertEquals(start[field], changed[field])
        val jewelry = changed["jewelryData"]!!.jsonObject
        assertEquals(16, jewelry.size)
        assertEquals(start["jewelryData"]!!.jsonObject["future"], jewelry["future"])
        assertEquals(JsonPrimitive("existing"), jewelry[id]!!.jsonObject["rune1"])
        assertEquals(JsonPrimitive(true), jewelry[id]!!.jsonObject["extra"])
        ItemCatalog.ofKind(ItemKind.JEWELRY).forEach { assertEquals(JsonPrimitive(100), jewelry[it.key.id]!!.jsonObject["durability"]) }
    }
    @Test fun jewelryInitializesOnlyMissingLists() {
        val changed = edit(root(), ItemSelection(jewelry = true))
        assertEquals(JsonArray(emptyList()), changed.root["jewelryPlayerData"])
        assertEquals(JsonArray(emptyList()), changed.root["jewelryBlueprints"])
        assertTrue(ItemEngine.edit(changed.bytes, ItemSelection(jewelry = true)).changes.isEmpty())
    }
    @Test fun otherVersionAndWrongStructureAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { edit(JsonObject(root() + ("AppVersion" to JsonPrimitive(80300))), ItemSelection(tapes = true)) }
        assertThrows(IllegalArgumentException::class.java) { edit(root(",\"materials\":[]"), ItemSelection(tapes = true)) }
        assertThrows(IllegalArgumentException::class.java) { ItemCodec.decode("invalid".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { edit(root(), ItemSelection()) }
    }
    @Test fun privateSampleOfflineRegressionLeavesSourceBytesUntouched() {
        val path = System.getProperty("item.baseline").orEmpty()
        assumeTrue("Optional private fixture was not supplied", path.isNotBlank())
        val file = File(path)
        val bytes = file.readBytes()
        val original = ItemEngine.inspect(bytes)
        val selection = ItemSelection(tapes = true, blueprints = true, facilities = true, jewelry = true,
            increments = ItemCatalog.ofKind(ItemKind.QUANTITY).associate { it.key to 1000 },
            amounts = ItemCatalog.ofKind(ItemKind.AMOUNT).associate { it.key to 123 })
        val patch = ItemEngine.edit(bytes, selection)
        assertEquals(patch.root, ItemCodec.decode(patch.bytes))
        val editedFields = setOf("materials", "seeds", "tokenTickets", "blueprints", "itemUnlock", "jewelryData")
        original.filterKeys { it !in editedFields }.forEach { (key, value) -> assertEquals(key, value, patch.root[key]) }
        for (field in listOf("materials", "seeds", "tokenTickets", "blueprints", "jewelryData")) {
            original[field]!!.jsonObject.filterKeys { ItemKey(field, it) !in ItemCatalog.byKey }.forEach { (key, value) ->
                assertEquals("$field.$key", value, patch.root[field]!!.jsonObject[key])
            }
        }
        selection.increments.forEach { (key, _) -> assertEquals(ItemEngine.quantity(original, key) + 1000, ItemEngine.quantity(patch.root, key)) }
        assertArrayEquals(bytes, file.readBytes())
    }
}
