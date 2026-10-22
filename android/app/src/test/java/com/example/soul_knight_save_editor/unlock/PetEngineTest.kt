package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PetEngineTest {
    private fun xml(extra: String = "") = """<map><string name="42_c0_unlock">false</string>
        <string name="42_p0_unlock">false</string><string name="42_p17_unlock">false</string>
        <string name="42_p55_unlock">False</string><string name="42_p56_unlock">true</string>
        <int name="OpenRijTest_42" value="1"/><string name="unrelated">keep</string>$extra</map>""".toByteArray()
    private fun game(cat: Boolean = false, version: Int = 80600) = UnlockEngine.xor(buildJsonObject {
        put("heroUnlock", buildJsonObject { put("Hero", false) })
        put("skinLock", buildJsonObject { put("Hero", JsonArray(emptyList())) })
        put("petUnlock", buildJsonObject {
            val entries = PetCatalog.entries.filter { it.index <= if (version == 80600) 56 else 57 }
            val ids = entries.take(17).map { it.id } + "Hide" + entries.drop(17).map { it.id }
            ids.forEach { id -> put(id, id == "EscapeMonkey" || id == "Cat" && cat) }
        })
        put("untouched", 29)
    }.toString().toByteArray())
    private fun rejected(block: () -> Unit) = assertThrows(Exception::class.java) { block() }

    @Test fun mappedEntriesExcludeHideAndIncludeNewestPets() {
        assertEquals(57, PetCatalog.entries.size)
        assertEquals((0..57).filter { it != 17 }, PetCatalog.entries.map { it.index })
        assertEquals("CatMecha", PetCatalog.entries.single { it.index == 55 }.id)
        assertEquals("EscapeMonkey", PetCatalog.entries.single { it.index == 56 }.id)
        assertEquals(PetDefinition(57, "FishChipKing", "鱼干之王"), PetCatalog.entries.last())
    }
    @Test fun pairedUnlockChangesOnlyExistingMappedEntries() {
        val before = game()
        val prefs = xml()
        val result = PetEngine.unlock(before, prefs, "42")
        val old = UnlockEngine.game(before)
        val updated = UnlockEngine.game(result.game!!)
        assertEquals(old - "petUnlock", updated - "petUnlock")
        assertEquals(JsonPrimitive(true), updated["petUnlock"]!!.jsonObject["Cat"])
        assertEquals(JsonPrimitive(true), updated["petUnlock"]!!.jsonObject["CatMecha"])
        assertEquals(JsonPrimitive(false), updated["petUnlock"]!!.jsonObject["Hide"])
        val nodes = UnlockEngine.prefs(result.prefs)
        assertEquals("true", nodes.getValue("42_p0_unlock").textContent)
        assertEquals("true", nodes.getValue("42_p55_unlock").textContent)
        assertEquals("false", nodes.getValue("42_p17_unlock").textContent)
        assertEquals("keep", nodes.getValue("unrelated").textContent)
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        val again = PetEngine.unlock(result.game, result.prefs, "42")
        assertTrue(again.changes.isEmpty())
        assertArrayEquals(result.prefs, again.prefs)
        assertArrayEquals(result.game, again.game)
    }
    @Test fun xmlOnlyAndAccountScopingDoNotCreateMissingKeys() {
        val input = xml("""<string name="99_p0_unlock">false</string>""")
        val result = PetEngine.unlock(null, input, "42")
        assertNull(result.game)
        val nodes = UnlockEngine.prefs(result.prefs)
        assertEquals("false", nodes.getValue("99_p0_unlock").textContent)
        assertFalse("42_p1_unlock" in nodes)
        assertEquals("false", nodes.getValue("42_p17_unlock").textContent)
    }
    @Test fun mismatchedMirrorAndUnknownStateRejectWholePreview() {
        rejected { PetEngine.unlock(game(cat = true), xml(), "42") }
        rejected { PetEngine.unlock(game(), String(xml()).replace(">False</string>", ">unknown</string>").toByteArray(), "42") }
        rejected { PetEngine.unlock(game(), xml(), "99") }
    }
    @Test fun bothKnownOrdersUnlockOnlyExistingKeysAndKeepHide() {
        for (version in listOf(80600, 80700)) {
            val before = game(version = version)
            val extra = if (version == 80700) """<string name="42_p57_unlock">false</string>""" else ""
            val prefs = xml(extra)
            val patch = PetEngine.unlock(before, prefs, "42")
            assertTrue(PetEngine.inspect(patch.game, patch.prefs, "42").all { it.unlocked })
            val old = UnlockEngine.game(before)
            val updated = UnlockEngine.game(patch.game!!)
            assertEquals(old - "petUnlock", updated - "petUnlock")
            assertEquals(old.getValue("petUnlock").jsonObject.keys.toList(), updated.getValue("petUnlock").jsonObject.keys.toList())
            assertEquals(JsonPrimitive(false), updated.getValue("petUnlock").jsonObject["Hide"])
            assertEquals(version == 80700, "FishChipKing" in updated.getValue("petUnlock").jsonObject)
            assertEquals(version == 80700, "42_p57_unlock" in UnlockEngine.prefs(patch.prefs))
            assertFalse("42_p1_unlock" in UnlockEngine.prefs(patch.prefs))
        }
    }
    @Test fun expertCanSelectFishChipKingWithoutUnlockingOtherPets() {
        val before = game(version = 80700)
        val prefs = xml("""<string name="42_p57_unlock">false</string>""")
        val patch = PetEngine.unlock(before, prefs, "42", setOf("FishChipKing"))
        val updated = UnlockEngine.game(patch.game!!).getValue("petUnlock").jsonObject
        assertEquals(JsonPrimitive(true), updated["FishChipKing"])
        assertEquals(JsonPrimitive(false), updated["Cat"])
        assertEquals("false", UnlockEngine.prefs(patch.prefs).getValue("42_p0_unlock").textContent)
        assertEquals(UnlockEngine.game(before).getValue("petUnlock").jsonObject - "FishChipKing", updated - "FishChipKing")
        rejected { PetEngine.unlock(before, xml(), "42", setOf("FishChipKing")) }
        rejected { PetEngine.unlock(game(), prefs, "42", setOf("FishChipKing")) }
        rejected { PetEngine.unlock(before, prefs, "42", setOf("Hide")) }
        rejected { PetEngine.unlock(before, prefs, "42", setOf("unknown")) }
    }
    @Test fun unknownReorderedOrInconsistent87MirrorsStillReject() {
        val before = UnlockEngine.game(game(version = 80700))
        val pets = before.getValue("petUnlock").jsonObject
        val prefs = xml("""<string name="42_p57_unlock">false</string>""")
        for (invalid in listOf(JsonObject(pets + ("FuturePet" to JsonPrimitive(false))),
            JsonObject(pets.toList().reversed().toMap()), JsonObject(pets + ("FishChipKing" to JsonPrimitive("false"))),
            JsonObject(pets + ("FishChipKing" to JsonPrimitive(true))))) {
            val game = UnlockEngine.xor(JsonObject(before + ("petUnlock" to invalid)).toString().toByteArray())
            rejected { PetEngine.unlock(game, prefs, "42") }
        }
        rejected { PetEngine.unlock(game(version = 80700), xml("""<string name="42_p57_unlock">false</string><string name="99_c0_unlock">false</string>"""), "42") }
    }
    @Test fun xmlOnly87MappingNeverCreatesKeysOrTouchesOtherAccounts() {
        val prefs = xml("""<string name="42_p57_unlock">false</string><string name="99_p57_unlock">false</string>""")
        val patch = PetEngine.unlock(null, prefs, "42", setOf("FishChipKing"))
        val nodes = UnlockEngine.prefs(patch.prefs)
        assertEquals("true", nodes.getValue("42_p57_unlock").textContent)
        assertEquals("false", nodes.getValue("99_p57_unlock").textContent)
        assertEquals("false", nodes.getValue("42_p17_unlock").textContent)
        assertNull(patch.game)
    }
    @Test fun combinedPetCharacterAndItemPreviewUsesOnePlan() {
        val prefs = SaveFile("/prefs.xml", xml(), "1000:1000", "600", "")
        val gameFile = SaveFile("/game.data", game(), "1000:1000", "600", "")
        val itemFile = SaveFile("/item_data_42_.data", ItemCodec.encode(buildJsonObject {
            put("AppVersion", 80600)
            put("materials", buildJsonObject { put("material_cell", 7) })
        }), "1000:1000", "600", "")
        val snapshot = SaveSnapshot("com.example.game", 0, prefs, gameFile, listOf(itemFile))
        val increment = ItemKey("materials", "material_cell")
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(heroes = setOf(0), pets = true),
            ItemSelection(increments = mapOf(increment to 1000)))
        assertEquals(setOf(prefs.path, gameFile.path, itemFile.path), plan.outputs.keys)
        val updated = UnlockEngine.game(plan.outputs.getValue(gameFile.path))
        assertEquals(JsonPrimitive(true), updated["heroUnlock"]!!.jsonObject["Hero"])
        assertEquals(JsonPrimitive(true), updated["petUnlock"]!!.jsonObject["CatMecha"])
        assertEquals(1007, ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue(itemFile.path)), increment))
    }
    @Test fun privateBaselineIsReadOnlyAndMappingMatchesItsExistingKeys() {
        val path = System.getProperty("unlock.baseline", "") ?: ""
        assumeTrue("Optional private fixture not supplied", path.isNotEmpty())
        val root = File(path)
        val gameFile = File(root, "files/game.data").takeIf { it.exists() } ?: File(root, "game.data")
        val prefsFile = File(root, "shared_prefs/com.ChillyRoom.DungeonShooter.v2.playerprefs.xml").takeIf { it.exists() }
            ?: File(root, "playerprefs.xml")
        val before = gameFile.readBytes()
        val prefs = prefsFile.readBytes()
        val account = UnlockEngine.catalog(before, prefs).account
        val states = PetEngine.inspect(before, prefs, account)
        assertEquals(56, states.size)
        val result = PetEngine.unlock(before, prefs, account)
        assertTrue(PetEngine.inspect(result.game, result.prefs, account).all { it.unlocked })
        assertArrayEquals(before, gameFile.readBytes())
        assertArrayEquals(prefs, prefsFile.readBytes())
    }
}
