package com.example.soul_knight_save_editor.unlock

/** Stable IDs keep navigation and per-tab state independent of tab order and future additions. */
data class WorkspaceTab(val id: String, val label: String)
object WorkspaceTabs {
    val saves = WorkspaceTab("saves", "存档")
    val characters = WorkspaceTab("characters", "角色")
    val items = WorkspaceTab("items", "物品")
    val weapons = WorkspaceTab("weapons", "武器")
}

fun AssistantState.selectTab(tab: WorkspaceTab): AssistantState = copy(
    page = AssistantPage.WORKSPACE, workspaceTab = tab.id, preview = null, previewMode = null
)

fun AssistantState.selectPackage(value: String): AssistantState = copy(
    packageName = value, pinnedTarget = null, snapshot = null, catalog = null, petStates = emptyList(), petMessage = "请先扫描宠物存档", accounts = emptyList(),
    progression = emptyList(), weaponStates = emptyList(), expertItems = ItemChoices(), expertWeapons = WeaponChoices(), choices = ModeChoices(), itemChoices = ItemChoices(), itemRoot = null,
    weaponInfo = null, weaponMessage = "请先扫描武器存档",
    preview = null, previewMode = null, itemMessage = "请先扫描物品存档",
    page = AssistantPage.WORKSPACE, workspaceTab = WorkspaceTabs.saves.id,
    message = "已切换目标，请扫描这个版本的本地存档。"
)

/** Discard stale content while retaining a session location unless the target version changes. */
fun AssistantState.clearLoaded(message: String, clearTarget: Boolean = false): AssistantState = copy(
    snapshot = null, pinnedTarget = if (clearTarget) null else pinnedTarget, catalog = null, accounts = emptyList(),
    progression = emptyList(), weaponStates = emptyList(), petStates = emptyList(), choices = ModeChoices(),
    itemRoot = null, itemChoices = ItemChoices(), expertItems = ItemChoices(), expertWeapons = WeaponChoices(),
    weaponInfo = null, preview = null, previewMode = null, message = message,
    itemMessage = "请先读取存档", weaponMessage = "请先读取存档", petMessage = "请先读取存档"
)
