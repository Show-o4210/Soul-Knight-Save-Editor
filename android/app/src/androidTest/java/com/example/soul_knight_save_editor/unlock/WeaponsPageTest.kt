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

class WeaponsPageTest {
    @get:Rule val compose = createComposeRule()
    private val scanned = AssistantState(mode = AssistantMode.QUICK, personalUseAccepted = true,
        catalog = Catalog(listOf("42"), "42", emptyList(), true), weaponInfo = WeaponInfo(416, 1),
        weaponMessage = "可处理 416 项武器获取记录，其中 1 项尚无键。")
    @Test fun quickWeaponSelectionEnablesSharedPreviewOnSmallScreen() {
        var state by mutableStateOf(scanned)
        var previews = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            WeaponContent(state, AssistantMode.QUICK, onSaves = {}, onSelect = { state = state.copy(choices = state.choices.copy(quickWeapons = it)) },
                onPreview = { previews++ }, onConsent = {}, onBackups = {})
        } } }
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("weapons-list").performScrollToNode(hasText("全部武器获取次数 +8"))
        compose.onNodeWithText("全部武器获取次数 +8").performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, previews); assertTrue(state.choices.quickWeapons) }
    }
    @Test fun expertModeDoesNotApplyAQuickWeaponDraft() {
        compose.setContent { MaterialTheme {
            WeaponContent(scanned.copy(mode = AssistantMode.EXPERT, choices = ModeChoices(quickWeapons = true)), AssistantMode.EXPERT,
                onSaves = {}, onSelect = {}, onPreview = {}, onConsent = {}, onBackups = {})
        } }
        compose.onNodeWithText("全部武器获取次数 +8").assertDoesNotExist()
        compose.onNodeWithText("专家模式武器入口已预留。逐项选择与自定义次数后续提供。").assertIsDisplayed()
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
    }
}
