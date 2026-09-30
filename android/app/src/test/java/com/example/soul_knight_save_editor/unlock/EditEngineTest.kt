package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EditEngineTest {
    private val root = Json.parseToJsonElement("""{"AppVersion":80600,"materials":{"material_cell":7}}""").jsonObject
    private val action = ItemSelection(increments = mapOf(ItemKey("materials", "material_cell") to 1000))
    private fun file(name: String, bytes: ByteArray = ItemCodec.encode(root)) = SaveFile("/data/user/0/com.test.game/files/$name", bytes, "10100:10100", "600", "")
    private fun snapshot(vararg items: SaveFile) = SaveSnapshot("com.test.game", 0, null, null, items.toList())
    private fun game(root: JsonObject) = file("game.data", UnlockEngine.xor(root.toString().toByteArray()))
    private fun statistic(name: String) = file(name, StatisticCodec.encode(buildJsonObject { put("object2ObtainTime", buildJsonObject { put("weapon_361", 1) }) }))
    @Test fun weaponsBindByAccountAndWorkWithoutLegacyGameOrXml() {
        val item = file("item_data_42_.data")
        val stat = statistic("statistic_42_.data")
        val other = statistic("statistic_99_.data")
        val save = snapshot(item).copy(statistics = listOf(stat, other, statistic("statistic.data")))
        assertEquals(listOf("42", "99"), EditEngine.accounts(save))
        assertEquals(stat, EditEngine.statistic(save, "42"))
        assertNull(EditEngine.statisticAccount("statistic_42_.data.new"))
        assertNull(EditEngine.statisticAccount("statistic_42_xdata"))
        val plan = EditEngine.preview(save, "42", UnlockSelection(), ItemSelection(), true)
        assertEquals(setOf(stat.path), plan.outputs.keys)
        assertEquals(9, StatisticCodec.decode(plan.outputs.getValue(stat.path))["object2ObtainTime"]!!.jsonObject["weapon_361"]!!.jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) { EditEngine.preview(save, "99", UnlockSelection(), ItemSelection(), true) }
        assertThrows(IllegalArgumentException::class.java) { EditEngine.preview(save.copy(statistics = listOf(other, statistic("statistic.data"))), "42", UnlockSelection(), ItemSelection(), true) }
        val duplicate = stat.copy(path = stat.path.replace("/files/", "/files/sub/"))
        assertThrows(IllegalArgumentException::class.java) { EditEngine.preview(save.copy(statistics = listOf(stat, duplicate)), "42", UnlockSelection(), ItemSelection(), true) }
    }
    @Test fun weaponAndItemPlanCombinesAndUsesOnlyExistingSelectedReadingFlags() {
        val item = file("item_data_42_.data")
        val stat = statistic("statistic_42_.data")
        val prefs = file("prefs.xml", """<map><int name="42_c0_unlock" value="0"/><int name="OpenRijTest_42" value="1"/><int name="OpenRijTest_99" value="1"/></map>""".toByteArray())
        val save = snapshot(item).copy(prefs = prefs, statistics = listOf(stat))
        val plan = EditEngine.preview(save, "42", UnlockSelection(), action, true)
        assertEquals(setOf(item.path, stat.path, prefs.path), plan.outputs.keys)
        assertEquals(1007, ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue(item.path)), ItemKey("materials", "material_cell")))
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_99").getAttribute("value"))
    }
    @Test fun standaloneItemDoesNotRequireGameOrXml() {
        val item = file("item_data_42_.data")
        val state = snapshot(item)
        assertEquals(listOf("42"), EditEngine.accounts(state))
        val plan = EditEngine.preview(state, "42", UnlockSelection(), action)
        assertEquals(setOf(item.path), plan.outputs.keys)
        assertEquals(1007, ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue(item.path)), ItemKey("materials", "material_cell")))
    }
    @Test fun defaultShardNeverStandsInForAnAccountShard() {
        val default = file("item_data.data")
        val named = file("item_data_42_.data")
        val state = snapshot(default, named)
        assertEquals(listOf("42"), EditEngine.accounts(state))
        assertEquals(named, EditEngine.item(state, "42"))
        assertThrows(IllegalArgumentException::class.java) { EditEngine.item(state, "99") }
        assertNull(EditEngine.itemAccount("item_data_42_.data.new"))
        assertNull(EditEngine.itemAccount("item_data_42_xdata"))
        assertNull(EditEngine.itemAccount("setting_42_.data"))
    }
    @Test fun duplicateShardIsRejectedAndOtherAccountsRemainUntouched() {
        val item = file("item_data_42_.data")
        val other = file("item_data_99_.data")
        val duplicate = item.copy(path = item.path.replace("/files/", "/files/sub/"))
        assertThrows(IllegalArgumentException::class.java) { EditEngine.preview(snapshot(item, duplicate), "42", UnlockSelection(), action) }
        val plan = EditEngine.preview(snapshot(item, other), "99", UnlockSelection(), action)
        assertEquals(setOf(other.path), plan.outputs.keys)
    }
    @Test fun gameWithoutLegacyCharacterOrItemDataDoesNotBlockItems() {
        val item = file("item_data_42_.data")
        val save = snapshot(item).copy(game = game(buildJsonObject { put("untouched", true) }))
        val plan = EditEngine.preview(save, "42", UnlockSelection(), action)
        assertEquals(setOf(item.path), plan.outputs.keys)
    }
    @Test fun existingMirrorIsUpdatedButMismatchedMirrorIsRefused() {
        val item = file("item_data_42_.data")
        val save = snapshot(item).copy(game = game(buildJsonObject { put("itemData", root); put("untouched", true) }))
        val plan = EditEngine.preview(save, "42", UnlockSelection(), action)
        val updated = Json.parseToJsonElement(UnlockEngine.utf8(UnlockEngine.xor(plan.outputs.getValue(save.game!!.path)))).jsonObject
        assertEquals(ItemCodec.decode(plan.outputs.getValue(item.path)), updated["itemData"])
        assertEquals(JsonPrimitive(true), updated["untouched"])
        val mismatch = save.copy(game = game(buildJsonObject { put("itemData", JsonObject(emptyMap())) }))
        assertThrows(IllegalArgumentException::class.java) { EditEngine.preview(mismatch, "42", UnlockSelection(), action) }
    }
    @Test fun itemEditUpdatesOnlyTheSelectedAccountsExistingReadingFlags() {
        val xml = """<map><int name="42_c0_unlock" value="0"/><int name="99_c0_unlock" value="0"/><int name="OpenRijTest_42" value="1"/><int name="OpenRijTest_99" value="1"/></map>""".toByteArray()
        val prefs = file("prefs.xml", xml)
        val save = snapshot(file("item_data_42_.data")).copy(prefs = prefs)
        val plan = EditEngine.preview(save, "42", UnlockSelection(), action)
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_99").getAttribute("value"))
        assertEquals("0", nodes.getValue("42_c0_unlock").getAttribute("value"))
        assertFalse("OpenNewtonJsonTest_42" in nodes)
    }
    @Test fun combinedCharacterAndItemEditKeepsBothChanges() {
        val item = file("item_data_42_.data")
        val prefs = file("prefs.xml", """<map><int name="42_c0_unlock" value="0"/></map>""".toByteArray())
        val gameRoot = Json.parseToJsonElement("""{"heroUnlock":{"Hero":false},"skinLock":{"Hero":[]}}""").jsonObject
        val save = snapshot(item).copy(prefs = prefs, game = game(JsonObject(gameRoot + ("itemData" to root))))
        val plan = EditEngine.preview(save, "42", UnlockSelection(heroes = setOf(0)), action)
        assertEquals(3, plan.outputs.size)
        val patchedGame = UnlockEngine.game(plan.outputs.getValue(save.game!!.path))
        assertEquals(JsonPrimitive(true), patchedGame["heroUnlock"]!!.jsonObject["Hero"])
        assertEquals(ItemCodec.decode(plan.outputs.getValue(item.path)), patchedGame["itemData"])
    }
}
