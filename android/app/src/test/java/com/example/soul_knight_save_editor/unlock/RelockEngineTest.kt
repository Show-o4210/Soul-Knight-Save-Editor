package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelockEngineTest {
    private data class Fixture(val game: ByteArray, val prefs: ByteArray)
    private val scope = RelockScope("com.test.game", 0)
    private val bili = RelockScope("com.knight.union.bili", 0, "8.7.0", 80700)
    private val names = listOf("Knight", "Ranger", "Mage")

    private fun fixture(locked: Boolean = false, choices: Boolean = true, account: String = "42", skin: Int = -13,
        heroNames: List<String> = names, skinCount: Int = 2, skillCount: Int = 2): Fixture {
        val nodes = mutableListOf<String>()
        val roles = linkedMapOf<String, JsonElement>()
        val skins = linkedMapOf<String, JsonElement>()
        val skills = linkedMapOf<String, JsonElement>()
        heroNames.forEachIndexed { index, name ->
            val unlocked = index == 0 || !locked
            nodes += "<string name=\"${account}_c${index}_unlock\">$unlocked</string>"
            roles[name] = JsonPrimitive(unlocked)
            skins[name] = JsonArray((0 until skinCount).map { id ->
                val state = if (id == 0 || !locked) 1 else skin
                nodes += "<int name=\"${account}_c${index}_skin$id\" value=\"$state\"/>"
                buildJsonObject { put("Key", id); put("Value", state); put("future", 9) }
            })
            skills[name] = JsonArray((0 until skillCount).map { id ->
                val state = id == 0 || !locked
                nodes += "<int name=\"${account}_c_${name}_skill_${id}_unlock\" value=\"${if (state) 1 else 0}\"/>"
                buildJsonObject { put("Key", id); put("Value", state); put("future", "keep") }
            })
        }
        val fields = linkedMapOf<String, JsonElement>("heroUnlock" to JsonObject(roles), "skinLock" to JsonObject(skins),
            "heroSkillUnlock" to JsonObject(skills), "heroLevel" to buildJsonObject { put("Ranger", 7) },
            "future" to buildJsonObject { put("unchanged", "yes") })
        if (choices) {
            fields["currentSkinDatas"] = buildJsonObject { put("Ranger", 1); put("Mage", 0) }
            fields["heroSkill"] = buildJsonObject { put("Ranger", 1); put("Mage", 0) }
            fields["superHeroInHeroRoom"] = JsonPrimitive(1)
            nodes += "<int name=\"${account}_c_Ranger_skill\" value=\"1\"/>"
            nodes += "<int name=\"${account}_c_Mage_skill\" value=\"0\"/>"
            nodes += "<int name=\"${account}_super_hero_in_hero_room\" value=\"1\"/>"
        }
        nodes += "<int name=\"OpenRijTest_$account\" value=\"1\"/>"
        nodes += "<int name=\"OpenNewtonJsonTest_$account\" value=\"1\"/>"
        nodes += "<string name=\"token\">KEEP</string>"
        return Fixture(UnlockEngine.xor(JsonObject(fields).toString().toByteArray()), "<map>${nodes.joinToString("")}</map>".toByteArray())
    }

    private fun original(f: Fixture = fixture(locked = true, choices = false), s: RelockScope = scope, account: String = "42") =
        RelockOriginal(s.packageName, s.user, account, f.prefs)
    private fun patch(f: Fixture, selection: UnlockSelection, originals: List<RelockOriginal> = listOf(original()), s: RelockScope = scope) =
        RelockEngine.patch(f.game, f.prefs, "42", s, originals, selection.copy(relock = true))
    private fun editGame(f: Fixture, edit: (JsonObject) -> JsonObject) = f.copy(game = UnlockEngine.xor(edit(UnlockEngine.game(f.game)).toString().toByteArray()))
    private fun pref(f: Fixture, key: String, old: String, replacement: String) = f.copy(prefs = String(f.prefs)
        .replace("name=\"$key\" value=\"$old\"", "name=\"$key\" value=\"$replacement\"").toByteArray())
    private fun value(bytes: ByteArray, name: String) = UnlockEngine.prefs(bytes).getValue(name).let { if (it.hasAttribute("value")) it.getAttribute("value") else it.textContent }

    @Test fun restoresOriginalValuesAndAllAffectedCurrentChoicesWhileKeepingBasesAndOtherContent() {
        val before = fixture()
        val result = patch(before, UnlockSelection(heroes = setOf(0, 1), skins = setOf(SkinId(1, 0), SkinId(1, 1)),
            skillIds = setOf(SkillId(1, 0), SkillId(1, 1))))
        val root = UnlockEngine.game(result.game!!)
        assertFalse(root.getValue("heroUnlock").jsonObject.getValue("Ranger").jsonPrimitive.boolean)
        assertTrue(root.getValue("heroUnlock").jsonObject.getValue("Knight").jsonPrimitive.boolean)
        assertEquals("-13", value(result.prefs, "42_c1_skin1"))
        assertEquals("1", value(result.prefs, "42_c1_skin0"))
        assertEquals("0", value(result.prefs, "42_c_Ranger_skill_1_unlock"))
        assertEquals("1", value(result.prefs, "42_c_Ranger_skill_0_unlock"))
        assertEquals(0, root.getValue("currentSkinDatas").jsonObject.getValue("Ranger").jsonPrimitive.int)
        assertEquals(0, root.getValue("heroSkill").jsonObject.getValue("Ranger").jsonPrimitive.int)
        assertEquals("0", value(result.prefs, "42_c_Ranger_skill"))
        assertEquals(0, root.getValue("superHeroInHeroRoom").jsonPrimitive.int)
        assertEquals("0", value(result.prefs, "42_super_hero_in_hero_room"))
        assertEquals("0", value(result.prefs, "OpenRijTest_42"))
        assertEquals("0", value(result.prefs, "OpenNewtonJsonTest_42"))
        assertEquals("KEEP", value(result.prefs, "token"))
        assertEquals(UnlockEngine.game(before.game).getValue("future"), root.getValue("future"))
        assertEquals(UnlockEngine.game(before.game).getValue("heroLevel"), root.getValue("heroLevel"))
        assertEquals(UnlockEngine.game(before.game).getValue("skinLock").jsonObject.getValue("Mage"), root.getValue("skinLock").jsonObject.getValue("Mage"))
        assertEquals(9, root.getValue("skinLock").jsonObject.getValue("Ranger").jsonArray[1].jsonObject.getValue("future").jsonPrimitive.int)
        assertArrayEquals(fixture().game, before.game)
        assertArrayEquals(fixture().prefs, before.prefs)
    }

    @Test fun newestKnownLockedOriginalWinsAndUnlockedHistoryDoesNotHideIt() {
        val current = fixture()
        val newer = fixture(locked = true, choices = false, skin = -20).let {
            it.copy(prefs = String(it.prefs).replace("name=\"42_c1_unlock\">false", "name=\"42_c1_unlock\">0").toByteArray())
        }
        val result = patch(current, UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1))),
            listOf(original(current), original(newer), original()))
        assertEquals("-20", value(result.prefs, "42_c1_skin1"))
        assertEquals("0", value(result.prefs, "42_c1_unlock"))
        assertTrue(result.changes.any { "解锁前原值" in it })
    }

    @Test fun originalsCannotCrossPackageAndroidUserOrAccount() {
        val wrong = listOf(original(s = scope.copy(packageName = "com.other.game")), original(s = scope.copy(user = 10)),
            original(fixture(locked = true, choices = false, account = "84"), account = "84"))
        val inventory = RelockEngine.inspect(fixture().game, fixture().prefs, "42", scope, wrong)
        assertTrue(inventory.heroes.isEmpty())
        assertTrue(inventory.skins.isEmpty())
        assertTrue(inventory.skills.isEmpty())
        assertTrue(inventory.skipped.any { "没有已知" in it })
    }

    @Test fun observedBiliReferenceIsScopedAndOriginalAlwaysTakesPriority() {
        val f = fixture(choices = false)
        val fromReference = patch(f, UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1)), skillIds = setOf(SkillId(1, 1))), emptyList(), bili)
        assertEquals("2000", value(fromReference.prefs, "42_c1_skin1"))
        assertEquals("false", value(fromReference.prefs, "42_c1_unlock"))
        assertEquals("0", value(fromReference.prefs, "42_c_Ranger_skill_1_unlock"))
        val fromOriginal = patch(f, UnlockSelection(skins = setOf(SkinId(1, 1))), listOf(original(s = bili)), bili)
        assertEquals("-13", value(fromOriginal.prefs, "42_c1_skin1"))
        for (s in listOf(bili.copy(packageName = "com.ChillyRoom.DungeonShooter"), bili.copy(versionName = "8.6.0"),
            bili.copy(versionName = null), bili.copy(versionCode = 80701))) {
            val inventory = RelockEngine.inspect(f.game, f.prefs, "42", s)
            assertTrue(inventory.heroes.isEmpty() && inventory.skins.isEmpty() && inventory.skills.isEmpty())
        }
    }

    @Test fun observedReferenceContainsExactlyTheLockedCatalogAndLeavesUnknownSkinUntouched() {
        val heroNames = listOf("Knight", "Ranger", "Mage", "Assassin", "Alchemist", "Engineer", "Vampire", "Paladin", "Elves", "Werewolf", "Priest", "Druid", "Robot", "Viking", "Necromancer", "Officer", "Taoist", "Transcendent", "Envoy", "Beheaded", "Ninja", "SpecialForces", "Airbender", "Warlock", "Miner", "TrapMaster", "CostumePrince", "Doctor", "SwordMaster", "Lancer", "Warliege", "ArcaneKnight", "Astrologist", "Fighter", "Joker", "Bard", "Shooter", "AiGirl", "Captain", "YinYang", "GunsExpert", "LadyChef")
        val f = fixture(choices = false, heroNames = heroNames, skinCount = 50, skillCount = 3)
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", bili)
        assertEquals(41, inventory.heroes.size)
        assertEquals(749, inventory.skins.size)
        assertEquals(62, inventory.skills.size)
        assertFalse(SkinId(23, 3) in inventory.skins)
        val result = patch(f, UnlockSelection(skins = setOf(SkinId(23, 3))), emptyList(), bili)
        assertArrayEquals(f.game, result.game)
        assertArrayEquals(f.prefs, result.prefs)
        assertTrue(result.changes.single().contains("没有已知的皮肤原锁值"))
    }

    @Test fun mirrorConflictsDisableOnlyThoseTargets() {
        val f = editGame(fixture()) { root -> JsonObject(root + mapOf(
            "heroUnlock" to JsonObject(root.getValue("heroUnlock").jsonObject + ("Ranger" to JsonPrimitive(false))),
            "skinLock" to JsonObject(root.getValue("skinLock").jsonObject + ("Ranger" to buildJsonArray { add(buildJsonObject { put("Key", 0); put("Value", 1) }); add(buildJsonObject { put("Key", 1); put("Value", 0) }) })),
            "heroSkillUnlock" to JsonObject(root.getValue("heroSkillUnlock").jsonObject + ("Ranger" to buildJsonArray { add(buildJsonObject { put("Key", 0); put("Value", true) }); add(buildJsonObject { put("Key", 1); put("Value", false) }) }))
        )) }
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", scope, listOf(original()))
        assertFalse(1 in inventory.heroes)
        assertFalse(SkinId(1, 1) in inventory.skins)
        assertFalse(SkillId(1, 1) in inventory.skills)
        assertTrue(2 in inventory.heroes && SkinId(2, 1) in inventory.skins && SkillId(2, 1) in inventory.skills)
    }

    @Test fun roleRelockResetsChoicesWithoutLockingUnselectedSkinOrSkill() {
        val result = patch(fixture(), UnlockSelection(heroes = setOf(1)))
        val root = UnlockEngine.game(result.game!!)
        assertEquals("1", value(result.prefs, "42_c1_skin1"))
        assertEquals("1", value(result.prefs, "42_c_Ranger_skill_1_unlock"))
        assertEquals(0, root.getValue("currentSkinDatas").jsonObject.getValue("Ranger").jsonPrimitive.int)
        assertEquals("0", value(result.prefs, "42_c_Ranger_skill"))
        assertEquals("0", value(result.prefs, "42_super_hero_in_hero_room"))
    }

    @Test fun lockingSelectedSkinAndSkillResetsOnlyTheirChoices() {
        val result = patch(fixture(), UnlockSelection(skins = setOf(SkinId(1, 1)), skillIds = setOf(SkillId(1, 1))))
        val root = UnlockEngine.game(result.game!!)
        assertTrue(root.getValue("heroUnlock").jsonObject.getValue("Ranger").jsonPrimitive.boolean)
        assertEquals(1, root.getValue("superHeroInHeroRoom").jsonPrimitive.int)
        assertEquals("0", value(result.prefs, "42_c_Ranger_skill"))
        assertEquals(0, root.getValue("currentSkinDatas").jsonObject.getValue("Ranger").jsonPrimitive.int)
        assertEquals(0, root.getValue("currentSkinDatas").jsonObject.getValue("Mage").jsonPrimitive.int)
    }

    @Test fun missingCurrentChoiceFieldsAreNotCreated() {
        val f = fixture(choices = false)
        val result = patch(f, UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1)), skillIds = setOf(SkillId(1, 1))))
        val root = UnlockEngine.game(result.game!!)
        assertFalse("currentSkinDatas" in root || "heroSkill" in root || "superHeroInHeroRoom" in root)
        val nodes = UnlockEngine.prefs(result.prefs)
        assertFalse("42_c_Ranger_skill" in nodes || "42_super_hero_in_hero_room" in nodes)
    }

    @Test fun malformedCurrentSkinDoesNotBlockIndependentSkillOrOtherHero() {
        val f = editGame(fixture()) { JsonObject(it + ("currentSkinDatas" to buildJsonObject { put("Ranger", "unknown"); put("Mage", 0) })) }
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", scope, listOf(original()))
        assertFalse(1 in inventory.heroes || SkinId(1, 1) in inventory.skins)
        assertTrue(SkillId(1, 1) in inventory.skills && 2 in inventory.heroes && SkinId(2, 1) in inventory.skins)
    }

    @Test fun inconsistentSelectedSkillDisablesRelatedRoleAndSkillWhileSkinRemainsAvailable() {
        val f = pref(fixture(), "42_c_Ranger_skill", "1", "0")
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", scope, listOf(original()))
        assertFalse(1 in inventory.heroes || SkillId(1, 1) in inventory.skills)
        assertTrue(SkinId(1, 1) in inventory.skins && 2 in inventory.heroes)
    }

    @Test fun inconsistentHallRoleOnlyDisablesTheRolesNamedByEitherMirror() {
        val f = pref(fixture(heroNames = names + "Assassin"), "42_super_hero_in_hero_room", "1", "2")
        val originals = listOf(original(fixture(locked = true, choices = false, heroNames = names + "Assassin")))
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", scope, originals)
        assertFalse(1 in inventory.heroes || 2 in inventory.heroes)
        assertTrue(3 in inventory.heroes && SkinId(1, 1) in inventory.skins && SkillId(1, 1) in inventory.skills)
        val result = patch(f, UnlockSelection(heroes = setOf(1, 2, 3)), originals)
        val root = UnlockEngine.game(result.game!!)
        assertTrue(root.getValue("heroUnlock").jsonObject.getValue("Ranger").jsonPrimitive.boolean)
        assertTrue(root.getValue("heroUnlock").jsonObject.getValue("Mage").jsonPrimitive.boolean)
        assertFalse(root.getValue("heroUnlock").jsonObject.getValue("Assassin").jsonPrimitive.boolean)
        assertEquals(1, root.getValue("superHeroInHeroRoom").jsonPrimitive.int)
        assertEquals("2", value(result.prefs, "42_super_hero_in_hero_room"))
    }

    @Test fun unavailableBaseCannotBeSilentlyUnlockedToMakeFallbackPossible() {
        val f = pref(fixture(), "42_c1_skin0", "1", "0")
        val inventory = RelockEngine.inspect(f.game, f.prefs, "42", scope, listOf(original()))
        assertFalse(1 in inventory.heroes || SkinId(1, 1) in inventory.skins)
        assertTrue(SkillId(1, 1) in inventory.skills)
        val result = patch(f, UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1))))
        assertArrayEquals(f.game, result.game)
        assertArrayEquals(f.prefs, result.prefs)
        assertEquals("0", value(result.prefs, "42_c1_skin0"))
    }

    @Test fun xmlOnlyIsExplicitlyUnavailableAndUnlockEntryRejectsRelockMarker() {
        val f = fixture()
        val inventory = RelockEngine.inspect(null, f.prefs, "42", scope, listOf(original()))
        assertTrue(inventory.heroes.isEmpty() && inventory.skins.isEmpty() && inventory.skills.isEmpty())
        assertTrue(inventory.skipped.single().contains("XML 单文件"))
        assertThrows(IllegalArgumentException::class.java) { UnlockEngine.unlock(f.game, f.prefs, "42", UnlockSelection(heroes = setOf(1), relock = true)) }
    }

    @Test fun repeatedRelockLeavesAlreadyLockedBytesAndReadingFlagsUntouched() {
        val selected = UnlockSelection(heroes = setOf(1), skins = setOf(SkinId(1, 1)), skillIds = setOf(SkillId(1, 1)))
        val once = patch(fixture(), selected)
        val twice = patch(Fixture(once.game!!, once.prefs), selected)
        assertArrayEquals(once.game, twice.game)
        assertArrayEquals(once.prefs, twice.prefs)
        assertTrue(twice.changes.isEmpty())
    }
}
