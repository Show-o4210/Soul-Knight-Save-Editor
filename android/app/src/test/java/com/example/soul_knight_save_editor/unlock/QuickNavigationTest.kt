package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class QuickNavigationTest {
    @Test fun settingsReturnToTheQuickTabThatOpenedThem() {
        val state = AssistantState(mode = AssistantMode.QUICK, page = AssistantPage.BACKUPS)
            .navigate(AssistantPage.SETTINGS)
        assertEquals(AssistantPage.BACKUPS, state.settingsReturnPage)
        assertEquals(AssistantPage.BACKUPS, state.navigate(state.settingsReturnPage).page)
    }

    @Test fun switchingTabsAndOpeningSettingsDiscardUnconfirmedPreview() {
        val state = AssistantState(mode = AssistantMode.QUICK, previewMode = AssistantMode.QUICK,
            preview = UnlockPatch(null, byteArrayOf(1), listOf("example")))
        val backup = state.navigate(AssistantPage.BACKUPS)
        assertNull(backup.preview)
        assertNull(backup.previewMode)
        assertNull(backup.navigate(AssistantPage.SETTINGS).preview)
    }

    @Test fun settingsFromUnlockReturnToUnlock() {
        val state = AssistantState(mode = AssistantMode.QUICK).navigate(AssistantPage.SETTINGS)
        assertEquals(AssistantPage.WORKSPACE, state.settingsReturnPage)
    }
}
