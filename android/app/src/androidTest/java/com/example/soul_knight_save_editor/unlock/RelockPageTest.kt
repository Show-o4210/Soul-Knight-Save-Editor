package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RelockPageTest {
    @get:Rule val compose = createComposeRule()
    private val inventory = RelockInventory(setOf(1), setOf(SkinId(1, 2)), setOf(SkillId(1, 1)),
        listOf("c1_skin3 的原锁值未知，已跳过"))
    private val scanned = AssistantState(mode = AssistantMode.QUICK, personalUseAccepted = true,
        catalog = Catalog(listOf("42"), "42", listOf(
            Hero(0, "Knight", true, mapOf(0 to 1)),
            Hero(1, "Ranger", true, mapOf(0 to 1, 2 to 1, 3 to 1))), false),
        progression = listOf(HeroProgress(1, 7, null, mapOf(0 to true, 1 to true), null)),
        relockInventory = inventory, relockMessage = "已找到解锁前原值。",
        choices = ModeChoices(quickRelock = true))

    @Test fun quickRelockEnablesSharedPreviewAndShowsSkippedReasonsOnSmallScreen() {
        var state by mutableStateOf(scanned)
        var previews = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            RelockCharacterContent(state, AssistantMode.QUICK, onRelock = {},
                onQuick = { roles, skins, skills -> state = state.copy(choices = state.choices.copy(
                    quickRoles = roles, quickSkins = skins, quickSkills = skills)) }, onSelect = {},
                onPreview = { previews++ }, onSaves = {}, onBackups = {}, onConsent = {})
        } } }
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText("重新锁定已确认的全部皮肤"))
        compose.onNodeWithText("重新锁定已确认的全部皮肤").performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled().performClick()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText("查看跳过说明（1 项）"))
        compose.onNodeWithText("查看跳过说明（1 项）").performClick()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText(inventory.skipped.single()))
        compose.onNodeWithText(inventory.skipped.single()).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, previews)
            assertTrue(state.choices.quickSkins)
            assertFalse(state.choices.quickRoles || state.choices.quickSkills || state.choices.quickLevels || state.choices.quickPets)
        }
    }

    @Test fun expertRelockDisablesBaseAndUnknownSkinButAllowsKnownSkin() {
        var state by mutableStateOf(scanned.copy(mode = AssistantMode.EXPERT,
            choices = ModeChoices(expert = UnlockSelection(relock = true))))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            RelockCharacterContent(state, AssistantMode.EXPERT, onRelock = {}, onQuick = { _, _, _ -> },
                onSelect = { state = state.copy(choices = state.choices.copy(expert = it)) },
                onPreview = {}, onSaves = {}, onBackups = {}, onConsent = {})
        } } }
        compose.onNodeWithText("重新锁定已确认的全部皮肤").assertDoesNotExist()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText("基础角色保留"))
        compose.onNodeWithText("基础角色保留").assertIsNotEnabled()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText("展开皮肤（3 项）"))
        compose.onNodeWithText("展开皮肤（3 项）").performClick()
        compose.onNodeWithTag("characters-relock-list").performScrollToNode(hasText("皮肤 #3 · 锁值未知或镜像不可用，跳过"))
        compose.onNodeWithText("皮肤 #3 · 锁值未知或镜像不可用，跳过").assertIsNotEnabled()
        compose.onNodeWithText("重新锁定皮肤 #2").performScrollTo().performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(setOf(SkinId(1, 2)), state.choices.expert.skins)
            assertTrue(state.choices.expert.relock)
        }
    }
}
