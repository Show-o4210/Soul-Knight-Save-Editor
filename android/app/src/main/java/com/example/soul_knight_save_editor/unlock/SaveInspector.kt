package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.JsonObject

data class AccountInspection(
    val characters: Result<Catalog>, val progression: Result<List<HeroProgress>>,
    val pets: Result<List<PetState>>, val items: Result<JsonObject>, val weapons: Result<List<WeaponState>>,
    val weaponSkins: Result<WeaponSkinInventory>,
    val weaponEvolution: Result<WeaponEvolutionInventory>, val mythic: Result<MythicInventory>
)

/** Independent feature reports from one captured snapshot; no device access and no inferred account fallback. */
object SaveInspector {
    fun inspect(snapshot: SaveSnapshot, account: String, dataMode: SaveDataMode? = null): AccountInspection {
        require(account in EditEngine.accounts(snapshot))
        val selectedSnapshot = dataMode?.select(snapshot) ?: snapshot
        fun needItems() {
            if (dataMode != null) require(selectedSnapshot.items.any { EditEngine.itemAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 物品存档"
            }
        }
        fun needStatistics() {
            if (dataMode != null) require(selectedSnapshot.statistics.any { EditEngine.statisticAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 统计存档"
            }
        }
        fun needEvolution() {
            if (dataMode != null) require(selectedSnapshot.weaponSkins.any { EditEngine.weaponSkinAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 武器进化存档"
            }
        }
        val prefs = selectedSnapshot.prefs
        val characters = runCatching { UnlockEngine.catalog(selectedSnapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val progression = runCatching { UnlockEngine.progression(selectedSnapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val pets = runCatching { PetEngine.inspect(selectedSnapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val items = runCatching { needItems(); ItemEngine.inspect(requireNotNull(EditEngine.item(selectedSnapshot, account)) { "没有与此账号对应的物品存档" }.bytes) }
        val weapons = runCatching { needStatistics(); needItems(); EditEngine.weaponStates(selectedSnapshot, account) }
        val weaponSkins = runCatching { needEvolution(); needItems(); EditEngine.weaponSkinInventory(selectedSnapshot, account) }
        val evolution = runCatching { needEvolution(); needItems(); EditEngine.weaponEvolutionInventory(selectedSnapshot, account) }
        val mythic = runCatching { needItems(); EditEngine.mythicInventory(selectedSnapshot, account) }
        return AccountInspection(characters, progression, pets, items, weapons, weaponSkins, evolution, mythic)
    }
}

fun AssistantState.itemsFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK) itemChoices else expertItems
fun AssistantState.weaponsFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK)
    WeaponSelection(allPlusEight = choices.quickWeapons) else expertWeapons.selection()

fun AssistantState.weaponSkinsFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK) quickWeaponSkins else expertWeaponSkins
fun AssistantState.weaponEvolutionFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK) quickWeaponEvolution else expertWeaponEvolution
fun AssistantState.mythicFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK) quickMythic else expertMythic
