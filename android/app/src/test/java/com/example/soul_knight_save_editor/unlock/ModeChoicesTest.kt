package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class ModeChoicesTest {
    private val catalog = Catalog(listOf(""), "", listOf(
        Hero(0, "Knight", true, mapOf(0 to 1, 6 to -6)),
        Hero(9, "NewHero", false, mapOf(70 to 999)),
        Hero(12, "UnknownRole", null, mapOf(8 to 0))), false)
    @Test fun quickModeStartsWithNothingSelected() {
        assertEquals(UnlockSelection(), ModeChoices().selection(AssistantMode.QUICK, catalog))
    }
    @Test fun quickModeDiscoversAllExistingSkinsWithoutInventingRoles() {
        val selection = ModeChoices(quickRoles = true, quickSkins = true).selection(AssistantMode.QUICK, catalog)
        assertEquals(setOf(0, 9), selection.heroes)
        assertEquals(setOf(SkinId(0, 0), SkinId(0, 6), SkinId(9, 70), SkinId(12, 8)), selection.skins)
        assertFalse(selection.levels)
        assertFalse(selection.skills)
    }
    @Test fun expertAndQuickChoicesStayIndependent() {
        val chosen = UnlockSelection(skins = setOf(SkinId(0, 6)))
        val choices = ModeChoices(expert = chosen)
        assertEquals(chosen, choices.selection(AssistantMode.EXPERT, catalog))
        assertEquals(UnlockSelection(), choices.selection(AssistantMode.QUICK, catalog))
    }
    @Test fun quickCanSelectSkinsWithoutRoles() {
        val selection = ModeChoices(quickSkins = true).selection(AssistantMode.QUICK, catalog)
        assertTrue(selection.heroes.isEmpty())
        assertEquals(4, selection.skins.size)
    }
    @Test fun levelsAndSkillsAreQuickOnlyAndOptIn() {
        val choices = ModeChoices(quickLevels = true, quickSkills = true)
        assertEquals(UnlockSelection(levels = true, skills = true), choices.selection(AssistantMode.QUICK, catalog))
        assertEquals(UnlockSelection(), choices.selection(AssistantMode.EXPERT, catalog))
    }
    @Test fun switchingCharacterActionClearsOnlyThatModesCharacterDraft() {
        val expert = UnlockSelection(heroes = setOf(9), levels = true, petIds = setOf("pet"))
        val choices = ModeChoices(quickRoles = true, quickSkins = true, quickLevels = true,
            quickSkills = true, quickPets = true, quickWeapons = true, expert = expert)
        val quickRelock = choices.withRelock(AssistantMode.QUICK, true)
        assertTrue(quickRelock.quickRelock)
        assertTrue(quickRelock.quickWeapons)
        assertEquals(expert, quickRelock.expert)
        assertFalse(quickRelock.quickRoles || quickRelock.quickSkins || quickRelock.quickLevels ||
            quickRelock.quickSkills || quickRelock.quickPets)
        val expertRelock = choices.withRelock(AssistantMode.EXPERT, true)
        assertEquals(UnlockSelection(relock = true), expertRelock.expert)
        assertTrue(expertRelock.quickRoles && expertRelock.quickPets && expertRelock.quickWeapons)
        assertEquals(choices, choices.withRelock(AssistantMode.FREE, true))
    }
    @Test fun quickRelockSelectsOnlyVerifiedInventoryAndNeverLevelsOrPets() {
        val inventory = RelockInventory(setOf(9), setOf(SkinId(9, 70)), setOf(SkillId(9, 2)), listOf("锁值未知"))
        val choices = ModeChoices(quickRoles = true, quickSkins = true, quickLevels = true,
            quickSkills = true, quickPets = true, quickRelock = true)
        val selected = choices.selection(AssistantMode.QUICK, catalog, inventory)
        assertEquals(UnlockSelection(heroes = inventory.heroes, skins = inventory.skins,
            skillIds = inventory.skills, relock = true), selected)
        assertFalse(selected.levels || selected.skills || selected.pets)
        assertEquals(UnlockSelection(relock = true), choices.selection(AssistantMode.QUICK, catalog))
    }
    @Test fun relockModeAndDraftAreIndependentBetweenQuickAndExpert() {
        val expert = UnlockSelection(heroes = setOf(9), relock = true)
        val choices = ModeChoices(quickSkins = true, expert = expert)
        assertFalse(choices.relocking(AssistantMode.QUICK))
        assertTrue(choices.relocking(AssistantMode.EXPERT))
        assertFalse(choices.relocking(AssistantMode.FREE))
        assertEquals(expert, choices.selection(AssistantMode.EXPERT, catalog))
        assertFalse(choices.selection(AssistantMode.QUICK, catalog).relock)
        assertEquals(UnlockSelection(), choices.withRelock(AssistantMode.EXPERT, false).expert)
        assertEquals(choices, choices.withRelock(AssistantMode.EXPERT, true))
    }
}
