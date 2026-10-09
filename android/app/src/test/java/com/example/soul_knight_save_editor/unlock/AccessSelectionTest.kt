package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class AccessSelectionTest {
    @Test fun existingUsersDefaultToNativeRoot() {
        assertEquals(AccessBackend.NATIVE_ROOT, AssistantState().accessBackend)
        assertTrue(AssistantState(accessBackend = AccessBackend.SHIZUKU_ROOT).accessStatus.startsWith("Shizuku Root"))
    }

    @Test fun switchingInvalidatesAllContentAndPreviewButKeepsNavigation() {
        val state = AssistantState(mode = AssistantMode.EXPERT, page = AssistantPage.SETTINGS,
            snapshot = SaveSnapshot("com.test.game", 10, null, null), accounts = listOf("42"),
            pinnedTarget = PinnedSaveTarget("com.test.game", 10, "42", null, null, emptyList(), emptyList(), false),
            preview = SavePlan(mapOf("game.data" to byteArrayOf(1)), listOf("change")),
            previewMode = AssistantMode.EXPERT, choices = ModeChoices(quickRoles = true),
            candidates = listOf(GameCandidate("com.test.game", evidence = emptyList())))
        val switched = state.selectAccessBackend(AccessBackend.SHIZUKU_ROOT)
        assertEquals(AccessBackend.SHIZUKU_ROOT, switched.accessBackend)
        assertEquals(AssistantPage.SETTINGS, switched.page)
        assertEquals(AssistantMode.EXPERT, switched.mode)
        assertNull(switched.snapshot)
        assertNull(switched.pinnedTarget)
        assertNull(switched.preview)
        assertNull(switched.previewMode)
        assertTrue(switched.accounts.isEmpty())
        assertTrue(switched.candidates.isEmpty())
        assertEquals(ModeChoices(), switched.choices)
    }

    @Test fun operationAndPendingJournalBothBlockSwitching() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantState(busy = true).selectAccessBackend(AccessBackend.SHIZUKU_ROOT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssistantState(pending = listOf("unfinished")).selectAccessBackend(AccessBackend.SHIZUKU_ROOT)
        }
    }

    @Test fun choosingCurrentBackendDoesNotDiscardAValidPreview() {
        val state = AssistantState(preview = SavePlan(mapOf("game.data" to byteArrayOf(1)), listOf("change")))
        assertSame(state, state.selectAccessBackend(AccessBackend.NATIVE_ROOT))
    }
}
