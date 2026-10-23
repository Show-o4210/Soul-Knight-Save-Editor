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
    private fun withUpgrades() = scanned.copy(
        weaponEvolutionInventory = WeaponEvolutionInventory(80700, kotlinx.serialization.json.JsonObject(emptyMap()),
            listOf(WeaponEvolutionState(WeaponEvolutionCatalog.entries.first(), 0, false)), 0),
        mythicInventory = MythicInventory(80700, kotlinx.serialization.json.JsonObject(emptyMap()),
            listOf(MythicState(MythicCatalog.entries.first(), -1, false)), 0))
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
        compose.onNodeWithText("逐项选择").assertIsDisplayed()
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
    }
    @Test fun skinOnlyQuickDraftEnablesSharedPreviewAndDoesNotSelectObtainCounts() {
        var state by mutableStateOf(scanned.copy(weaponSkinInventory = WeaponSkinInventory(80700,
            kotlinx.serialization.json.JsonObject(emptyMap()), emptyList(), 0)))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            WeaponContent(state, AssistantMode.QUICK, onSaves = {}, onSelect = {}, onPreview = {}, onConsent = {}, onBackups = {},
                onSkins = { state = state.copy(quickWeaponSkins = it) })
        } } }
        compose.onNodeWithTag("weapon-skins-tab").performClick()
        compose.onNodeWithTag("weapon-skins-list").performScrollToNode(hasText("解锁已确认的全部武器皮肤"))
        compose.onNodeWithText("解锁已确认的全部武器皮肤").performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(state.quickWeaponSkins.unlockAll)
            assertTrue(state.quickWeaponSkins.current.isEmpty())
            assertFalse(state.choices.quickWeapons)
        }
    }

    @Test fun evolutionAndMythicQuickDraftsEnableSharedPreviewWithoutObtainCountsOnSmallScreen() {
        var state by mutableStateOf(withUpgrades())
        var previews = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            WeaponContent(state, AssistantMode.QUICK, onSaves = {},
                onSelect = { state = state.copy(choices = state.choices.copy(quickWeapons = it)) },
                onPreview = { previews++ }, onConsent = {}, onBackups = {},
                onEvolution = { state = state.copy(quickWeaponEvolution = it) },
                onMythic = { state = state.copy(quickMythic = it) })
        } } }
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("weapon-evolution-tab").performScrollTo().performClick()
        compose.onNodeWithTag("weapon-upgrades-list").performScrollToNode(hasText("完成全部已确认武器的进化"))
        compose.onNodeWithText("完成全部已确认武器的进化").performScrollTo().performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(state.quickWeaponEvolution.allMax)
            assertFalse(state.quickMythic.hasChanges)
            assertFalse(state.choices.quickWeapons)
            state = state.copy(quickWeaponEvolution = WeaponEvolutionSelection())
        }
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("mythic-weapons-tab").performScrollTo().performClick()
        compose.onNodeWithTag("weapon-upgrades-list").performScrollToNode(hasText("全部升至 3 级（含解锁）"))
        compose.onNodeWithText("全部升至 3 级（含解锁）").performScrollTo().performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled().performClick()
        compose.onNodeWithTag("weapon-upgrades-list").performScrollToNode(hasText("全部基础解锁（0 级）"))
        compose.onNodeWithText("全部基础解锁（0 级）").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(2, previews)
            assertTrue(state.quickMythic.maxAll)
            assertTrue(state.quickMythic.unlockAll)
            assertFalse(state.quickWeaponEvolution.hasChanges)
            assertFalse(state.choices.quickWeapons)
            assertFalse(state.expertWeaponEvolution.hasChanges)
            assertFalse(state.expertMythic.hasChanges)
        }
    }

    @Test fun expertUpgradeTabsHideQuickActionsAndIgnoreQuickDraftsOnSmallScreen() {
        var state by mutableStateOf(withUpgrades().copy(mode = AssistantMode.EXPERT,
            quickWeaponEvolution = WeaponEvolutionSelection(allMax = true), quickMythic = MythicSelection(maxAll = true)))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) {
            WeaponContent(state, AssistantMode.EXPERT, onSaves = {}, onSelect = {}, onPreview = {}, onConsent = {}, onBackups = {},
                onEvolution = { state = state.copy(expertWeaponEvolution = it) },
                onMythic = { state = state.copy(expertMythic = it) })
        } } }
        compose.onNodeWithTag("weapon-evolution-tab").performScrollTo().performClick()
        compose.onNodeWithText("完成全部已确认武器的进化").assertDoesNotExist()
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("weapon-upgrades-list").performScrollToNode(hasText("完成进化（等级 1）"))
        compose.onNodeWithText("完成进化（等级 1）").performScrollTo().performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(setOf(WeaponEvolutionCatalog.entries.first().id), state.expertWeaponEvolution.levels.keys)
            assertTrue(state.quickWeaponEvolution.allMax)
            state = state.copy(expertWeaponEvolution = WeaponEvolutionSelection())
        }
        compose.onNodeWithTag("mythic-weapons-tab").performScrollTo().performClick()
        compose.onNodeWithText("全部基础解锁（0 级）").assertDoesNotExist()
        compose.onNodeWithText("全部升至 3 级（含解锁）").assertDoesNotExist()
        compose.onNodeWithTag("preview-all-edits").assertIsNotEnabled()
        compose.onNodeWithTag("weapon-upgrades-list").performScrollToNode(hasText("基础解锁", substring = false))
        compose.onNodeWithText("基础解锁", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("preview-all-edits").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(setOf(MythicCatalog.entries.first().id), state.expertMythic.unlock)
            assertTrue(state.quickMythic.maxAll)
            assertFalse(state.choices.quickWeapons)
        }
    }
}
