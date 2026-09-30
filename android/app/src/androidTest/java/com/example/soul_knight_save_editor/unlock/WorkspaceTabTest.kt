package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkspaceTabTest {
    @get:Rule val compose = createComposeRule()
    @Test fun extraTabsRemainReachableOnSmallScreens() {
        val future = WorkspaceTab("future", "后续功能")
        val tabs = listOf(WorkspaceTabs.saves, WorkspaceTabs.characters, WorkspaceTabs.items, WorkspaceTabs.weapons, future)
        var selected: String? = null
        compose.setContent { MaterialTheme { Box(Modifier.width(280.dp)) {
            WorkspaceTabBar(AssistantState(mode = AssistantMode.QUICK), tabs) { selected = it.id }
        } } }
        compose.onNodeWithTag("tab-future").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("future", selected) }
    }
    @Test fun busyTabsCannotChangeTheActiveTarget() {
        var clicked = false
        compose.setContent { MaterialTheme {
            WorkspaceTabBar(AssistantState(mode = AssistantMode.QUICK, busy = true), listOf(WorkspaceTabs.saves, WorkspaceTabs.items)) { clicked = true }
        } }
        compose.onNodeWithTag("tab-items").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(clicked) }
    }
}
