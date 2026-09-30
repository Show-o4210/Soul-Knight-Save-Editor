package com.example.soul_knight_save_editor.unlock

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApplyOutcomeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulWriteShowsCheckAndExpandableDetails() {
        var dismissed = false
        compose.setContent { MaterialTheme {
            ApplyOutcomeDialog(ApplyOutcome(true, "com.test.game", 2,
                listOf("宠物：已解锁", "物品：已增加"), emptyList())) { dismissed = true }
        } }
        compose.onNodeWithText("修改成功").assertIsDisplayed()
        compose.onNodeWithTag("apply-outcome-symbol").assertTextEquals("✓")
        compose.onNodeWithTag("apply-outcome-details").assertDoesNotExist()
        compose.onNodeWithTag("apply-outcome-toggle").performClick()
        compose.onNodeWithText("成功项（2）").assertIsDisplayed()
        compose.onNodeWithText("失败项（0）").assertIsDisplayed()
        compose.onNodeWithText("宠物：已解锁").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun failedWriteShowsReasonOnlyAfterExpansion() {
        compose.setContent { MaterialTheme {
            ApplyOutcomeDialog(ApplyOutcome(false, "com.test.game", 0,
                emptyList(), listOf("写回校验失败"))) {}
        } }
        compose.onNodeWithText("修改未完成").assertIsDisplayed()
        compose.onNodeWithText("写回校验失败").assertDoesNotExist()
        compose.onNodeWithTag("apply-outcome-toggle").performClick()
        compose.onNodeWithText("成功项（0）").assertIsDisplayed()
        compose.onNodeWithText("失败项（1）").assertIsDisplayed()
        compose.onNodeWithText("写回校验失败").assertIsDisplayed()
    }
}
