package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class QuickNavigationTest {
    @Test fun weaponSelectionDefaultsOffIsQuickOnlyAndClearsWithPackage() {
        val choices = ModeChoices(quickWeapons = true)
        assertFalse(ModeChoices().weapons(AssistantMode.QUICK))
        assertTrue(choices.weapons(AssistantMode.QUICK))
        assertFalse(choices.weapons(AssistantMode.EXPERT))
        val state = AssistantState(choices = choices, weaponInfo = WeaponInfo(416, 1)).selectTab(WorkspaceTabs.weapons)
        assertEquals("weapons", state.workspaceTab)
        assertTrue(state.selectTab(WorkspaceTabs.items).choices.quickWeapons)
        val next = state.selectPackage("com.other.game")
        assertFalse(next.choices.quickWeapons)
        assertNull(next.weaponInfo)
    }
    @Test fun petChoiceIsQuickOnlyAndDefaultsOff() {
        val catalog = Catalog(listOf("42"), "42", emptyList(), true)
        assertFalse(ModeChoices().selection(AssistantMode.QUICK, catalog).pets)
        val choices = ModeChoices(quickPets = true)
        assertTrue(choices.selection(AssistantMode.QUICK, catalog).pets)
        assertFalse(choices.selection(AssistantMode.EXPERT, catalog).pets)
        assertFalse(ModeChoices().quickPets)
    }
    @Test fun settingsReturnToTheQuickTabThatOpenedThem() {
        val state = AssistantState(mode = AssistantMode.QUICK, workspaceTab = WorkspaceTabs.items.id)
            .navigate(AssistantPage.SETTINGS).navigate(AssistantPage.BACKUPS)
            .navigate(AssistantPage.SETTINGS)
        assertEquals(AssistantPage.WORKSPACE, state.settingsReturnPage)
        assertEquals(WorkspaceTabs.items.id, state.navigate(state.settingsReturnPage).workspaceTab)
    }

    @Test fun switchingTabsAndOpeningSettingsDiscardUnconfirmedPreview() {
        val state = AssistantState(mode = AssistantMode.QUICK, previewMode = AssistantMode.QUICK,
            preview = SavePlan(mapOf("example" to byteArrayOf(1)), listOf("example")))
        val backup = state.navigate(AssistantPage.BACKUPS)
        assertNull(backup.preview)
        assertNull(backup.previewMode)
        assertNull(backup.navigate(AssistantPage.SETTINGS).preview)
    }

    @Test fun settingsFromUnlockReturnToUnlock() {
        val state = AssistantState(mode = AssistantMode.QUICK).navigate(AssistantPage.SETTINGS)
        assertEquals(AssistantPage.WORKSPACE, state.settingsReturnPage)
    }
    @Test fun tabsKeepDraftsButInvalidatePreviewAndAllowFutureDestinations() {
        val draft = ItemChoices(actions = ItemSelection(jewelry = true))
        val state = AssistantState(mode = AssistantMode.QUICK, choices = ModeChoices(quickLevels = true), itemChoices = draft,
            preview = SavePlan(mapOf("file" to byteArrayOf(1)), listOf("pending")), previewMode = AssistantMode.QUICK)
        val next = state.selectTab(WorkspaceTab("future-feature", "未来功能"))
        assertEquals("future-feature", next.workspaceTab)
        assertEquals(draft, next.itemChoices)
        assertTrue(next.choices.quickLevels)
        assertNull(next.preview)
        assertNull(next.previewMode)
    }
    @Test fun selectingAnotherVersionClearsEveryDraftAndSnapshot() {
        val state = AssistantState(packageName = "com.first.game", mode = AssistantMode.QUICK,
            snapshot = SaveSnapshot("com.first.game", 0, null, null),
            catalog = Catalog(listOf("42"), "42", emptyList(), true), accounts = listOf("42"),
            choices = ModeChoices(quickRoles = true, expert = UnlockSelection(heroes = setOf(0))),
            itemChoices = ItemChoices(actions = ItemSelection(tapes = true)),
            preview = SavePlan(mapOf("old" to byteArrayOf(1)), listOf("old")), previewMode = AssistantMode.QUICK)
        val selected = state.selectPackage("com.second.game")
        assertEquals("com.second.game", selected.packageName)
        assertEquals(WorkspaceTabs.saves.id, selected.workspaceTab)
        assertNull(selected.snapshot)
        assertNull(selected.catalog)
        assertNull(selected.itemRoot)
        assertNull(selected.preview)
        assertNull(selected.previewMode)
        assertTrue(selected.accounts.isEmpty())
        assertEquals(ModeChoices(), selected.choices)
        assertEquals(ItemChoices(), selected.itemChoices)
    }
}
