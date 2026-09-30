package com.example.soul_knight_save_editor.unlock

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AssistantNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun separatePagesNestedBackupsAndDiscoveryKeepOneSelectedTarget() {
        val settings = compose.activity.getSharedPreferences("assistant-ui", 0)
        val oldMode = settings.getString("mode", null)
        try {
            compose.activityRule.scenario.onActivity { ViewModelProvider(it)[AssistantModel::class.java].chooseMode(AssistantMode.QUICK) }
            if (compose.onAllNodesWithText("暂不使用写入").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("暂不使用写入").performClick()
            compose.onNodeWithTag("tab-saves").assertIsSelected()
            compose.onNodeWithTag("tab-characters").performClick()
            compose.onNodeWithText("前往存档").assertIsDisplayed()
            compose.onNodeWithTag("scan-selected-game").assertDoesNotExist()
            compose.onNodeWithTag("tab-items").performClick().assertIsSelected()
            compose.onNodeWithTag("tab-weapons").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("前往存档").assertIsDisplayed()
            compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("settings-backups").performClick()
            compose.onNodeWithText("把原件留在自己手里").assertIsDisplayed()
            compose.onNodeWithTag("tab-saves").assertDoesNotExist()
            Espresso.pressBack()
            compose.onNodeWithTag("settings-backups").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("tab-weapons").assertIsSelected()

            val args = InstrumentationRegistry.getArguments()
            if (args.getString("saveDiscovery") == "true") {
                val expected = requireNotNull(args.getString("expectedDiscoveryPackage"))
                compose.onNodeWithTag("tab-saves").performClick()
                compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("discover-games"))
                compose.onNodeWithTag("discover-games").performClick()
                compose.waitUntil(15000) { compose.onAllNodes(hasText("搜索完成。", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("saves-list").performScrollToNode(hasTestTag("select-$expected"))
                compose.onNodeWithTag("select-$expected").assertIsDisplayed()
                // No scan, candidate selection, backup restore or apply action is invoked by this test.
            }
        } finally {
            settings.edit().apply { if (oldMode == null) remove("mode") else putString("mode", oldMode) }.commit()
        }
    }
}
