package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class UnlockEngineTest {
    private fun xml(value: Int = -6, role: Boolean = false, uid: String = "42", extra: String = "") = """<?xml version="1.0" encoding="utf-8"?>
        <map><string name="${uid}_c0_unlock">$role</string><int name="${uid}_c0_skin3" value="$value" />
        <int name="OpenRijTest_$uid" value="1" /><int name="OpenNewtonJsonTest_$uid" value="1" />
        <int name="wins" value="29"/><string name="token">KEEP</string>$extra</map>""".toByteArray()
    private fun game(value: Int = -6, role: Boolean = false) = UnlockEngine.xor("""{"heroUnlock":{"LadyChef":$role},"skinLock":{"LadyChef":[{"Key":3,"Value":$value,"unknown":9}]},"heroLevel":{"LadyChef":7},"heroSkillUnlock":{"LadyChef":[{"Key":0,"Value":false}]},"wins":29,"future":{"x":[1,false,"abc"]}}""".toByteArray())
    private val selection = UnlockSelection(setOf(0), setOf(SkinId(0, 3)))
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }

    @Test fun roleAndSkinUnlockPreserveProgressAndUnknownFields() {
        val before = game()
        val prefs = xml()
        val output = UnlockEngine.unlock(before, prefs, "42", selection)
        val root = UnlockEngine.game(output.game!!)
        val previous = UnlockEngine.game(before)
        assertEquals(previous - setOf("heroUnlock", "skinLock"), root - setOf("heroUnlock", "skinLock"))
        assertEquals(true, root["heroUnlock"]!!.jsonObject["LadyChef"]!!.jsonPrimitive.boolean)
        assertEquals(1, root["skinLock"]!!.jsonObject["LadyChef"]!!.jsonArray[0].jsonObject["Value"]!!.jsonPrimitive.int)
        assertEquals(9, root["skinLock"]!!.jsonObject["LadyChef"]!!.jsonArray[0].jsonObject["unknown"]!!.jsonPrimitive.int)
        val nodes = UnlockEngine.prefs(output.prefs)
        assertEquals("29", nodes.getValue("wins").getAttribute("value"))
        assertEquals("KEEP", nodes.getValue("token").textContent)
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals(4, output.changes.size)
        assertArrayEquals(before, game())
        assertArrayEquals(prefs, xml())
    }
    @Test fun everyIntegerSkinStateUnlocksToOne() {
        for (value in listOf(-20, -13, -10, -6, 0, 1, 12000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val output = UnlockEngine.unlock(game(value), xml(value), "42", UnlockSelection(skins = setOf(SkinId(0, 3))))
            assertEquals(1, UnlockEngine.catalog(output.game, output.prefs).heroes.single().skins[3])
            assertEquals(false, UnlockEngine.catalog(output.game, output.prefs).heroes.single().unlocked)
        }
    }
    @Test fun xmlOnlyIsSupported() {
        val output = UnlockEngine.unlock(null, xml(), "42", selection)
        assertNull(output.game)
        val catalog = UnlockEngine.catalog(null, output.prefs)
        assertTrue(catalog.xmlOnly)
        assertEquals(true, catalog.heroes.single().unlocked)
        assertEquals(1, catalog.heroes.single().skins[3])
    }
    @Test fun doesNotReformatUnrelatedXml() {
        val input = xml()
        val output = UnlockEngine.unlock(null, input, "42", selection)
        val expected = String(input).replace(">false</string>", ">true</string>")
            .replace("name=\"42_c0_skin3\" value=\"-6\"", "name=\"42_c0_skin3\" value=\"1\"")
            .replace("name=\"OpenRijTest_42\" value=\"1\"", "name=\"OpenRijTest_42\" value=\"0\"")
            .replace("name=\"OpenNewtonJsonTest_42\" value=\"1\"", "name=\"OpenNewtonJsonTest_42\" value=\"0\"")
        assertEquals(expected, String(output.prefs))
    }
    @Test fun futureRolesAndSkinIdsAreDiscovered() {
        val prefs = """<map><string name="9_c77_unlock">false</string><int name="9_c77_skin999" value="-999"/></map>""".toByteArray()
        val result = UnlockEngine.unlock(null, prefs, "9", UnlockSelection(setOf(77), setOf(SkinId(77, 999))))
        assertEquals(1, UnlockEngine.catalog(null, result.prefs).heroes.single().skins[999])
        assertFalse(String(result.prefs).contains("OpenRijTest"))
    }
    @Test fun idempotentSecondUnlockHasNoChanges() {
        val once = UnlockEngine.unlock(game(), xml(), "42", selection)
        val twice = UnlockEngine.unlock(once.game, once.prefs, "42", selection)
        assertTrue(twice.changes.isEmpty())
        assertArrayEquals(once.game, twice.game)
        assertArrayEquals(once.prefs, twice.prefs)
    }
    @Test fun onlySelectedSkinChanges() {
        val input = xml(extra = """<int name="42_c0_skin4" value="12000"/>""")
        val output = UnlockEngine.unlock(null, input, "42", UnlockSelection(skins = setOf(SkinId(0, 3))))
        assertEquals("12000", UnlockEngine.prefs(output.prefs).getValue("42_c0_skin4").getAttribute("value"))
        assertEquals(false, UnlockEngine.catalog(null, output.prefs).heroes.single().unlocked)
    }
    @Test fun arbitraryUnlockKeyIsNotRecognized() {
        assertFalse(UnlockEngine.recognizesPrefs("""<map><int name="achievement_unlock" value="0"/></map>""".toByteArray()))
    }
    @Test fun accountSelectionIsRequiredWhenMultiple() {
        val input = xml(extra = """<int name="84_c0_skin3" value="-20"/>""")
        rejected { UnlockEngine.catalog(null, input) }
        val output = UnlockEngine.unlock(null, input, "84", UnlockSelection(skins = setOf(SkinId(0, 3))))
        assertEquals("-6", UnlockEngine.prefs(output.prefs).getValue("42_c0_skin3").getAttribute("value"))
        assertEquals("1", UnlockEngine.prefs(output.prefs).getValue("OpenRijTest_42").getAttribute("value"))
    }
    @Test fun multiAccountGamePairIsRejected() {
        rejected { UnlockEngine.catalog(game(), xml(extra = """<int name="84_c0_skin3" value="1"/>"""), "42") }
    }
    @Test fun mismatchedSkinMirrorIsRejected() { rejected { UnlockEngine.unlock(game(0), xml(-6), "42", selection) } }
    @Test fun mismatchedRoleMirrorIsRejected() { rejected { UnlockEngine.unlock(game(role = true), xml(), "42", selection) } }
    @Test fun missingGameSkinIsRejected() { rejected { UnlockEngine.unlock(game(), xml(extra = """<int name="42_c0_skin4" value="0"/>"""), "42", UnlockSelection(skins = setOf(SkinId(0, 4)))) } }
    @Test fun noUnknownSkinCreation() { rejected { UnlockEngine.unlock(null, xml(), "42", UnlockSelection(skins = setOf(SkinId(0, 99)))) } }
    @Test fun malformedPresentGameIsNotTreatedAsMissing() { rejected { UnlockEngine.catalog(byteArrayOf(1, 2), xml()) } }
    @Test fun duplicateXmlKeysRejected() { rejected { UnlockEngine.prefs(xml(extra = """<int name="42_c0_skin3" value="0"/>""")) } }
    @Test fun dtdRejected() { rejected { UnlockEngine.prefs("""<!DOCTYPE map [<!ENTITY x SYSTEM "file:///etc/passwd">]><map/>""".toByteArray()) } }
    @Test fun duplicateSkinIdsRejected() {
        val input = UnlockEngine.xor("""{"heroUnlock":{"Knight":true},"skinLock":{"Knight":[{"Key":0,"Value":1},{"Key":0,"Value":0}]}}""".toByteArray())
        rejected { UnlockEngine.game(input) }
    }
    @Test fun unknownReadingFlagValueRejected() { rejected { UnlockEngine.unlock(null, String(xml()).replace("name=\"OpenRijTest_42\" value=\"1\"", "name=\"OpenRijTest_42\" value=\"9\"").toByteArray(), "42", selection) } }
    @Test fun unprefixedXmlSupported() {
        val input = String(xml()).replace("42_", "").replace("Test_42", "Test").toByteArray()
        assertEquals(true, UnlockEngine.catalog(null, UnlockEngine.unlock(null, input, "", selection).prefs).heroes.single().unlocked)
    }
    @Test fun singleQuotesAndBooleanRoleSupported() {
        val input = """<map><boolean value='false' name='5_c0_unlock'/><int value='-6' name='5_c0_skin3'/></map>""".toByteArray()
        val result = UnlockEngine.unlock(null, input, "5", selection)
        assertEquals(true, UnlockEngine.catalog(null, result.prefs).heroes.single().unlocked)
        assertEquals(1, UnlockEngine.catalog(null, result.prefs).heroes.single().skins[3])
    }
    @Test fun quickLevelsAndSkillsUpdateOnlyExistingMirroredEntries() {
        val before = UnlockEngine.xor("""{"heroUnlock":{"Knight":true,"Robot":false},"skinLock":{"Knight":[],"Robot":[]},"heroLevel":{"Knight":3,"Robot":8},"heroSkillUnlock":{"Knight":[{"Key":0,"Value":false,"extra":9}],"Robot":[{"Key":2,"Value":true}]},"heroSkill":{"Knight":2},"wins":29}""".toByteArray())
        val prefs = """<map><int name="42_c0_unlock" value="1"/><int name="42_c1_unlock" value="0"/><int name="42_c0_level" value="3"/><int name="42_c1_level" value="8"/><int name="42_c_Knight_skill_0_unlock" value="0"/><int name="42_c_Robot_skill_2_unlock" value="1"/><string name="token">KEEP</string></map>""".toByteArray()
        val result = UnlockEngine.unlock(before, prefs, "42", UnlockSelection(levels = true, skills = true))
        val root = UnlockEngine.game(result.game!!)
        assertEquals(7, root["heroLevel"]!!.jsonObject.getValue("Knight").jsonPrimitive.int)
        assertEquals(8, root["heroLevel"]!!.jsonObject.getValue("Robot").jsonPrimitive.int)
        assertTrue(root["heroSkillUnlock"]!!.jsonObject.getValue("Knight").jsonArray[0].jsonObject.getValue("Value").jsonPrimitive.boolean)
        assertEquals(9, root["heroSkillUnlock"]!!.jsonObject.getValue("Knight").jsonArray[0].jsonObject.getValue("extra").jsonPrimitive.int)
        assertEquals(UnlockEngine.game(before) - setOf("heroLevel", "heroSkillUnlock"), root - setOf("heroLevel", "heroSkillUnlock"))
        assertEquals("KEEP", UnlockEngine.prefs(result.prefs).getValue("token").textContent)
        assertEquals(2, result.changes.size)
        assertTrue(UnlockEngine.unlock(result.game, result.prefs, "42", UnlockSelection(levels = true, skills = true)).changes.isEmpty())
    }
    @Test fun levelsAndSkillsRejectMissingOrMismatchedMirrors() {
        val level = """<int name="42_c0_level" value="7"/>"""
        val skill = """<int name="42_c_LadyChef_skill_0_unlock" value="0"/>"""
        rejected { UnlockEngine.unlock(game(), xml(extra = level.replace("value=\"7\"", "value=\"6\"") + skill), "42", UnlockSelection(levels = true, skills = true)) }
        val valid = xml(extra = level + skill)
        rejected { UnlockEngine.unlock(null, valid, "42", UnlockSelection(levels = true)) }
        rejected { UnlockEngine.unlock(game(), xml(extra = level), "42", UnlockSelection(skills = true)) }
        rejected { UnlockEngine.unlock(game(), xml(extra = skill), "42", UnlockSelection(levels = true)) }
    }
    @Test fun realPrivateBaselineReadAndPatchWithoutBundlingIt() {
        val path = System.getProperty("unlock.baseline", "") ?: ""
        assumeTrue("Optional private fixture not supplied", path.isNotEmpty())
        val root = File(path)
        val before = File(root, "files/game.data").readBytes()
        val prefs = File(root, "shared_prefs/com.ChillyRoom.DungeonShooter.v2.playerprefs.xml").readBytes()
        val catalog = UnlockEngine.catalog(before, prefs)
        val chef = catalog.heroes.single { it.name == "LadyChef" }
        assertEquals(41, chef.index)
        val output = UnlockEngine.unlock(before, prefs, catalog.account, UnlockSelection(skins = setOf(SkinId(chef.index, 3))))
        assertEquals(1, UnlockEngine.catalog(output.game, output.prefs).heroes.single { it.index == 41 }.skins[3])
        val changedRoot = UnlockEngine.game(output.game!!)
        assertEquals(UnlockEngine.game(before) - "skinLock", changedRoot - "skinLock")
        val all = UnlockEngine.unlock(before, prefs, catalog.account, UnlockSelection(catalog.heroes.filter { it.unlocked != null }.map { it.index }.toSet(), catalog.heroes.flatMap { h -> h.skins.keys.map { SkinId(h.index, it) } }.toSet()))
        assertTrue(UnlockEngine.catalog(all.game, all.prefs).heroes.all { it.unlocked != false && it.skins.values.all { v -> v == 1 } })
        val progression = UnlockEngine.unlock(before, prefs, catalog.account, UnlockSelection(levels = true, skills = true))
        val progressionRoot = UnlockEngine.game(progression.game!!)
        assertTrue(progressionRoot["heroLevel"]!!.jsonObject.values.all { it.jsonPrimitive.int >= 7 })
        assertTrue(progressionRoot["heroSkillUnlock"]!!.jsonObject.values.flatMap { it.jsonArray }.all { it.jsonObject.getValue("Value").jsonPrimitive.boolean })
        assertEquals(UnlockEngine.game(before) - setOf("heroLevel", "heroSkillUnlock"), progressionRoot - setOf("heroLevel", "heroSkillUnlock"))
        assertArrayEquals(before, File(root, "files/game.data").readBytes())
        assertArrayEquals(prefs, File(root, "shared_prefs/com.ChillyRoom.DungeonShooter.v2.playerprefs.xml").readBytes())
    }
}
