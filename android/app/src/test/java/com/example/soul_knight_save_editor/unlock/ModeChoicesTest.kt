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
}
