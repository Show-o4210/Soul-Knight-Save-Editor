package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.JsonObject

data class AccountInspection(
    val characters: Result<Catalog>, val progression: Result<List<HeroProgress>>,
    val pets: Result<List<PetState>>, val items: Result<JsonObject>, val weapons: Result<List<WeaponState>>
)

/** Independent feature reports from one captured snapshot; no device access and no inferred account fallback. */
object SaveInspector {
    fun inspect(snapshot: SaveSnapshot, account: String): AccountInspection {
        require(account in EditEngine.accounts(snapshot))
        val prefs = snapshot.prefs
        val characters = runCatching { UnlockEngine.catalog(snapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val progression = runCatching { UnlockEngine.progression(snapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val pets = runCatching { PetEngine.inspect(snapshot.game?.bytes, requireNotNull(prefs) { "未识别到角色 XML" }.bytes, account) }
        val items = runCatching { ItemEngine.inspect(requireNotNull(EditEngine.item(snapshot, account)) { "没有与此账号对应的物品存档" }.bytes) }
        val weapons = runCatching { EditEngine.weaponStates(snapshot, account) }
        return AccountInspection(characters, progression, pets, items, weapons)
    }
}

fun AssistantState.itemsFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK) itemChoices else expertItems
fun AssistantState.weaponsFor(mode: AssistantMode) = if (mode == AssistantMode.QUICK)
    WeaponSelection(allPlusEight = choices.quickWeapons) else expertWeapons.selection()
