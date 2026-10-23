package com.example.soul_knight_save_editor.unlock

/** Stable IDs keep navigation and per-tab state independent of tab order and future additions. */
data class WorkspaceTab(val id: String, val label: String)
object WorkspaceTabs {
    val saves = WorkspaceTab("saves", "存档")
    val characters = WorkspaceTab("characters", "角色")
    val items = WorkspaceTab("items", "物品")
    val weapons = WorkspaceTab("weapons", "武器")
    val free = WorkspaceTab("free", "自由编辑")
}

fun AssistantState.selectTab(tab: WorkspaceTab): AssistantState = copy(
    page = AssistantPage.WORKSPACE, workspaceTab = tab.id, preview = null, previewMode = null
)

fun AssistantState.selectPackage(value: String): AssistantState = copy(
    packageName = value, pinnedTarget = null, snapshot = null, catalog = null, petStates = emptyList(), petMessage = "请先扫描宠物存档", accounts = emptyList(),
    relockInventory = null, relockMessage = "请先读取存档以核对可重新锁定内容",
    freeSnapshot = null, freeSources = emptyList(), freeSelected = emptySet(), freeAccounts = emptyList(), freeAccount = null,
    freeSession = null, freeReview = null, freeOpenRequest = null,
    progression = emptyList(), weaponStates = emptyList(), expertItems = ItemChoices(), expertWeapons = WeaponChoices(), choices = ModeChoices(), itemChoices = ItemChoices(), itemRoot = null,
    weaponInfo = null, weaponMessage = "请先扫描武器存档",
    weaponSkinInventory = null, weaponSkinMessage = "请先读取武器皮肤存档",
    quickWeaponSkins = WeaponSkinSelection(), expertWeaponSkins = WeaponSkinSelection(),
    weaponEvolutionInventory = null, weaponEvolutionMessage = "请先读取武器进化存档",
    quickWeaponEvolution = WeaponEvolutionSelection(), expertWeaponEvolution = WeaponEvolutionSelection(),
    mythicInventory = null, mythicMessage = "请先读取神话武器存档",
    quickMythic = MythicSelection(), expertMythic = MythicSelection(),
    preview = null, previewMode = null, itemMessage = "请先扫描物品存档",
    page = AssistantPage.WORKSPACE, workspaceTab = WorkspaceTabs.saves.id,
    message = "已切换目标，请扫描这个版本的本地存档。"
)

/** Discard stale content while retaining a session location unless the target version changes. */
fun AssistantState.clearLoaded(message: String, clearTarget: Boolean = false): AssistantState = copy(
    snapshot = null, pinnedTarget = if (clearTarget) null else pinnedTarget, catalog = null, accounts = emptyList(),
    relockInventory = null, relockMessage = "请先读取存档以核对可重新锁定内容",
    freeSnapshot = null, freeSources = emptyList(), freeSelected = emptySet(), freeAccounts = emptyList(), freeAccount = null, freeReview = null,
    progression = emptyList(), weaponStates = emptyList(), petStates = emptyList(), choices = ModeChoices(),
    itemRoot = null, itemChoices = ItemChoices(), expertItems = ItemChoices(), expertWeapons = WeaponChoices(),
    weaponSkinInventory = null, weaponSkinMessage = "请先读取武器皮肤存档",
    quickWeaponSkins = WeaponSkinSelection(), expertWeaponSkins = WeaponSkinSelection(),
    weaponEvolutionInventory = null, weaponEvolutionMessage = "请先读取武器进化存档",
    quickWeaponEvolution = WeaponEvolutionSelection(), expertWeaponEvolution = WeaponEvolutionSelection(),
    mythicInventory = null, mythicMessage = "请先读取神话武器存档",
    quickMythic = MythicSelection(), expertMythic = MythicSelection(),
    weaponInfo = null, preview = null, previewMode = null, message = message,
    itemMessage = "请先读取存档", weaponMessage = "请先读取存档", petMessage = "请先读取存档"
)

fun AssistantState.afterStorageFailure(error: Exception): AssistantState {
    val invalidates = generateSequence<Throwable>(error) { it.cause }.any {
        it is SaveSnapshotChanged || it is AccessFailure && it.kind in setOf(
            AccessFailureKind.GAME_RUNNING, AccessFailureKind.PROCESS_CHECK_FAILED,
            AccessFailureKind.SOURCE_CHANGED, AccessFailureKind.TIMEOUT, AccessFailureKind.SESSION_DISCONNECTED)
    }
    val text = error.message ?: "操作未完成，已保存的备份仍保留"
    return if (invalidates) clearLoaded(text) else copy(message = text, preview = null, previewMode = null)
}
