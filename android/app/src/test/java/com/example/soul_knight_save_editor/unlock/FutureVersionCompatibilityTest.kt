package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FutureVersionCompatibilityTest {
    @get:Rule val temp = TemporaryFolder()
    private val packageName = "com.test.game"
    private val cell = ItemKey("materials", "material_cell")
    private val ticket = ItemKey("tokenTickets", "token_factor_GoodLuck_5")
    private val missingMaterial = ItemKey("materials", "material_iron")
    private val missingTicket = ItemKey("tokenTickets", "token_factor_BadLuck_5")
    private val item = Json.parseToJsonElement("""{
        "AppVersion":80900,
        "materials":{"material_cell":0,"material_wood":"6","future_material":12,"material_fish":5},
        "tokenTickets":{"token_factor_GoodLuck_5":2},
        "opaque":{"enabled":true}
    }""").jsonObject
    private val statistic = Json.parseToJsonElement("""{
        "object2ObtainTime":{"weapon_003":0,"weapon_007":"2","weapon_unconfirmed":15},
        "opaque":[1,2]
    }""").jsonObject

    private fun itemFile(mode: SaveDataMode, root: JsonObject = item): SaveFile =
        file("files/item_data_42_${mode.extension}", if (mode == SaveDataMode.LEGACY) ItemCodec.encode(root) else RijndaelCodec.encode(root))

    private fun statisticFile(mode: SaveDataMode, root: JsonObject = statistic): SaveFile =
        file("files/statistic_42_${mode.extension}", if (mode == SaveDataMode.LEGACY) StatisticCodec.encode(root) else RijndaelCodec.encode(root))

    private fun file(name: String, bytes: ByteArray) =
        SaveFile("/data/user/0/$packageName/$name", bytes, "1000:1000", "600", "")

    @Test fun actualInspectionAndPreviewAllowOnlyExistingKnownEntriesInBothEncodings() {
        for (mode in SaveDataMode.entries) {
            val itemFile = itemFile(mode)
            val statFile = statisticFile(mode)
            val snapshot = SaveSnapshot(packageName, 0, null, null, listOf(itemFile), listOf(statFile))
            val inspection = SaveInspector.inspect(snapshot, "42", mode)
            assertEquals(setOf(cell, ticket), ItemEngine.existingEntries(inspection.items.getOrThrow()).map { it.key }.toSet())
            assertEquals(listOf(WeaponState("weapon_003", 0, false)), inspection.weapons.getOrThrow())
            assertTrue(inspection.mythic.isFailure)

            val choices = ItemDrafts.step(item, ItemChoices(), "材料")
            assertEquals(mapOf(cell to 1000), choices.selection().increments)
            val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), choices.selection().copy(amounts = mapOf(ticket to 0)),
                weapons = true, dataMode = mode)
            assertEquals(setOf(itemFile.path, statFile.path), plan.outputs.keys)
            val changedItem = plan.outputs.getValue(itemFile.path)
            val changedStat = plan.outputs.getValue(statFile.path)
            assertEquals(ItemCodec.format(itemFile.bytes), ItemCodec.format(changedItem))
            assertEquals(StatisticCodec.format(statFile.bytes), StatisticCodec.format(changedStat))
            val expectedItem = replace(item, cell.field, cell.id, 1000).let { replace(it, ticket.field, ticket.id, 0) }
            assertEquals(expectedItem, ItemCodec.decode(changedItem))
            assertEquals(replace(statistic, "object2ObtainTime", "weapon_003", 8), StatisticCodec.decode(changedStat))
        }
    }

    @Test fun normalEditRejectsMissingUnknownInvalidAndUnsupportedEntries() {
        val itemBytes = ItemCodec.encode(item)
        val stats = StatisticCodec.encode(statistic)
        for (key in listOf(missingMaterial, missingTicket, ItemKey("materials", "material_wood"),
            ItemKey("materials", "future_material"), ItemKey("materials", "material_fish"))) {
            assertThrows(IllegalArgumentException::class.java) { ItemEngine.edit(itemBytes, ItemSelection(amounts = mapOf(key to 1))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ItemEngine.edit(itemBytes, ItemSelection(blueprints = true)) }
        for (id in listOf("weapon_006", "weapon_007", "weapon_unconfirmed")) {
            assertThrows(IllegalArgumentException::class.java) { WeaponEngine.edit(stats, itemBytes, WeaponSelection(increments = mapOf(id to 8))) }
        }
    }

    @Test fun optionalCompanionsDoNotReceiveMissingEntries() {
        val mainItem = itemFile(SaveDataMode.LEGACY)
        val mainStat = statisticFile(SaveDataMode.LEGACY)
        val companionItem = itemFile(SaveDataMode.MODERN, Json.parseToJsonElement("""{"AppVersion":80800}""").jsonObject)
        val companionStat = statisticFile(SaveDataMode.MODERN, buildJsonObject { put("object2ObtainTime", JsonObject(emptyMap())) })
        val prefs = file("shared_prefs/test.xml", """<map><int name="OpenRijTest_42" value="0"/></map>""".toByteArray())
        val snapshot = SaveSnapshot(packageName, 0, prefs, null, listOf(mainItem, companionItem), listOf(mainStat, companionStat))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(increments = mapOf(cell to 10)), weapons = true)
        assertEquals(setOf(mainItem.path, mainStat.path), plan.outputs.keys)
    }

    @Test fun aTestOnlyFileCopyCanStoreManuallyAddedEntriesInBothEncodings() {
        val folder = temp.newFolder()
        val itemWithAdditions = replace(item, missingMaterial.field, missingMaterial.id, 4).let {
            replace(it, missingTicket.field, missingTicket.id, 2)
        }
        val statsWithAddition = replace(statistic, "object2ObtainTime", "weapon_006", 8)
        for (mode in SaveDataMode.entries) {
            val originalItem = itemFile(mode)
            val originalStats = statisticFile(mode)
            val itemCopy = File(folder, originalItem.path.substringAfterLast('/'))
            val statCopy = File(folder, originalStats.path.substringAfterLast('/'))
            itemCopy.writeBytes(ItemCodec.encode(itemWithAdditions, originalItem.bytes))
            statCopy.writeBytes(StatisticCodec.encode(statsWithAddition, originalStats.bytes))
            assertEquals(ItemCodec.format(originalItem.bytes), ItemCodec.format(itemCopy.readBytes()))
            assertEquals(StatisticCodec.format(originalStats.bytes), StatisticCodec.format(statCopy.readBytes()))
            assertEquals(itemWithAdditions, ItemCodec.decode(itemCopy.readBytes()))
            assertEquals(statsWithAddition, StatisticCodec.decode(statCopy.readBytes()))
            assertEquals(item, ItemCodec.decode(originalItem.bytes))
            assertEquals(statistic, StatisticCodec.decode(originalStats.bytes))
        }
    }

    private fun replace(root: JsonObject, field: String, key: String, value: Int): JsonObject =
        JsonObject(root + (field to JsonObject(root.getValue(field).jsonObject + (key to JsonPrimitive(value)))))
}
