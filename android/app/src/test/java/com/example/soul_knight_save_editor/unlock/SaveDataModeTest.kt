package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SaveDataModeTest {
    private val directory = "/data/user/0/com.test.game"
    private val iron = ItemKey("materials", "material_iron")
    private fun file(name: String, bytes: ByteArray) = SaveFile("$directory/files/$name", bytes, "10100:10100", "600", "")
    private fun root(amount: Int) = buildJsonObject {
        put("AppVersion", 80700)
        put("materials", buildJsonObject { put("material_iron", amount); put("material_wood", 3) })
        put("untouched", "keep")
    }
    private fun prefs(flag: String? = "1") = file("prefs.xml", """<map>
        <string name="AccountSaveSettingsIds">{"42":"700"}</string>
        <string name="42_c0_unlock">false</string>
        ${if (flag == null) "" else "<int name=\"OpenRijTest_700\" value=\"$flag\"/><int name=\"OpenNewtonJsonTest_700\" value=\"$flag\"/>"}
        <int name="OpenRijTest_900" value="1"/><int name="OpenNewtonJsonTest_900" value="1"/>
    </map>""".trimIndent().toByteArray()).copy(path = "$directory/shared_prefs/player.xml")
    private fun snapshot(flag: String? = "1") = SaveSnapshot("com.test.game", 0, prefs(flag), null, listOf(
        file("item_data_42_.data", ItemCodec.encode(root(4))),
        file("item_data_42_.data.new", RijndaelCodec.encode(root(7)))))

    @Test fun initialChoiceUsesBoundConfigurationOrUniqueFormatAndDoesNotGuessAConflictingPair() {
        assertEquals(SaveDataMode.MODERN, SaveDataMode.initial(snapshot(), "42"))
        assertEquals(SaveDataMode.LEGACY, SaveDataMode.initial(snapshot("0"), "42"))
        assertNull(SaveDataMode.initial(snapshot(null), "42"))
        val original = snapshot(null)
        assertEquals(SaveDataMode.LEGACY, SaveDataMode.initial(original.copy(items = original.items.take(1)), "42"))
        assertEquals(SaveDataMode.MODERN, SaveDataMode.initial(original.copy(items = original.items.takeLast(1)), "42"))
        assertEquals(SaveDataMode.LEGACY, SaveDataMode.initial(original.copy(items = emptyList()), "42"))
        assertEquals(SaveDataMode.MODERN, SaveDataMode.initial(snapshot().copy(items = emptyList()), "42"))
    }

    @Test fun eachExplicitModeUsesItsOwnIncrementAndDoesNotWriteTheOtherFormat() {
        val original = snapshot()
        for (mode in SaveDataMode.entries) {
            val plan = EditEngine.preview(original, "42", UnlockSelection(),
                ItemSelection(increments = mapOf(iron to 1000)), dataMode = mode)
            val source = original.items.single { it.path.endsWith(mode.extension) }
            val other = original.items.single { it.path != source.path }
            assertFalse(plan.outputs.containsKey(other.path))
            val result = ItemCodec.decode(plan.outputs.getValue(source.path))
            assertEquals(if (mode == SaveDataMode.MODERN) 1007 else 1004, ItemEngine.quantity(result, iron))
            assertEquals(root(7) - "materials", result - "materials")
            val prefs = requireNotNull(original.prefs)
            val nodes = UnlockEngine.prefs(plan.outputs[prefs.path] ?: prefs.bytes)
            assertEquals(if (mode == SaveDataMode.MODERN) "1" else "0", nodes.getValue("OpenRijTest_700").getAttribute("value"))
            assertEquals(if (mode == SaveDataMode.MODERN) "1" else "0", nodes.getValue("OpenNewtonJsonTest_700").getAttribute("value"))
            assertEquals("1", nodes.getValue("OpenRijTest_900").getAttribute("value"))
        }
    }

    @Test fun modernXmlOnlyCharacterEditingPreservesExistingAndMissingReadingFlags() {
        for (flag in listOf("0", "1", null)) {
            val original = snapshot(flag).copy(items = emptyList())
            val plan = EditEngine.preview(original, "42", UnlockSelection(heroes = setOf(0)), ItemSelection(),
                dataMode = SaveDataMode.MODERN)
            val prefs = requireNotNull(original.prefs)
            val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
            assertEquals("true", nodes.getValue("42_c0_unlock").textContent)
            for (key in listOf("OpenRijTest_700", "OpenNewtonJsonTest_700")) {
                if (flag == null) assertFalse(key in nodes) else assertEquals(flag, nodes.getValue(key).getAttribute("value"))
            }
        }
    }

    @Test fun aMissingSelectedFormatReportsTheAffectedFeatureInsteadOfFallingBack() {
        val pair = snapshot(null)
        val original = pair.copy(items = pair.items.take(1))
        val report = SaveInspector.inspect(original, "42", SaveDataMode.MODERN)
        assertTrue(report.characters.isSuccess)
        assertEquals("未发现 .data.new 物品存档", report.items.exceptionOrNull()?.message)
        assertEquals("未发现 .data.new 统计存档", report.weapons.exceptionOrNull()?.message)
        assertEquals("未发现 .data.new 武器进化存档", report.weaponEvolution.exceptionOrNull()?.message)
        val error = assertThrows(IllegalArgumentException::class.java) {
            EditEngine.preview(original, "42", UnlockSelection(), ItemSelection(amounts = mapOf(iron to 9)),
                dataMode = SaveDataMode.MODERN)
        }
        assertTrue(error.message!!.contains(".data.new 物品存档"))
    }

    @Test fun commonGameMirrorKeepsItsOriginalWholeSnapshotAccountChecks() {
        val pair = snapshot()
        val game = file("game.data", UnlockEngine.xor(buildJsonObject { put("itemData", root(4)) }.toString().toByteArray()))
        val original = pair.copy(game = game)
        val plan = EditEngine.preview(original, "42", UnlockSelection(), ItemSelection(increments = mapOf(iron to 1)),
            dataMode = SaveDataMode.MODERN)
        assertFalse(plan.outputs.containsKey(pair.items.first().path))
        assertEquals(8, ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue(pair.items.last().path)), iron))
        val mirror = Json.parseToJsonElement(UnlockEngine.utf8(UnlockEngine.xor(plan.outputs.getValue(game.path)))).jsonObject
        assertEquals(8, ItemEngine.quantity(mirror.getValue("itemData").jsonObject, iron))
        val other = file("item_data_99_.data", ItemCodec.encode(root(4)))
        assertThrows(IllegalArgumentException::class.java) {
            EditEngine.preview(original.copy(items = original.items + other), "42", UnlockSelection(),
                ItemSelection(increments = mapOf(iron to 1)), dataMode = SaveDataMode.MODERN)
        }
    }
}
