package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun WeaponSkinContent(state: AssistantState, mode: AssistantMode, modifier: Modifier,
    onSaves: () -> Unit, onPreview: () -> Unit, onConsent: () -> Unit, onBackups: () -> Unit,
    onSelect: (WeaponSkinSelection) -> Unit) {
    var query by rememberSaveable(state.packageName, state.catalog?.account) { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val choices = state.weaponSkinsFor(mode)
    val inventory = state.weaponSkinInventory
    val visible = inventory?.states.orEmpty().filter { weapon ->
        (weapon.weapon.contains(query, true) || weapon.label.contains(query, true) || weapon.skins.any {
            it.id.contains(query, true) || it.label.contains(query, true)
        }) && (!selectedOnly || weapon.weapon in choices.current || weapon.skins.any { it.id in choices.unlock })
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).testTag("weapon-skins-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel(title = "武器皮肤", help = HelpTopics.weaponSkins) {
                Text(state.weaponSkinMessage)
                Text("解锁与当前皮肤分开设置；保留现有进化等级。", style = MaterialTheme.typography.bodySmall)
                if (state.catalog == null) OutlinedButton(onSaves, enabled = !state.busy) { Text("前往存档") }
                if (inventory != null && inventory.unknownWeapons > 0)
                    Text("另有 ${inventory.unknownWeapons} 把目录外武器，原样保留。", style = MaterialTheme.typography.bodySmall)
                if (!state.personalUseAccepted) TextButton(onConsent) { Text("先确认个人使用约定") }
            } }
            item { Status(state) }
            if (state.pending.isNotEmpty()) item { Panel {
                Text("有未完成的写回，请先恢复")
                Button(onBackups, enabled = !state.busy) { Text("前往备份与恢复") }
            } }
            if (state.catalog != null && mode == AssistantMode.QUICK) item {
                Panel(title = "批量解锁", help = HelpTopics.weaponSkins) {
                    CheckLine("解锁已确认的全部武器皮肤", choices.unlockAll, !state.busy && inventory != null,
                        { onSelect(WeaponSkinSelection(unlockAll = it)) })
                    Text("保留每把武器当前使用的皮肤。需要换肤时，请到专家模式逐项选择。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.catalog != null && mode == AssistantMode.EXPERT && inventory != null) {
                item { Panel(title = "查找与选择", help = HelpTopics.weaponSkins) {
                    OutlinedTextField(query, { query = it }, label = { Text("武器、皮肤名称或 ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("weapon-skin-search"))
                    CheckLine("只看本次待修改", selectedOnly, !state.busy) { selectedOnly = it }
                    Text("${visible.size} 把武器匹配 · ${choices.unlock.size} 个皮肤待解锁 · ${choices.current.size} 把待换肤")
                    TextButton({ onSelect(choices.copy(unlock = choices.unlock + visible.filter { it.error == null }
                        .flatMap { weapon -> weapon.skins.filter { it.id !in weapon.unlocked }.map { it.id } })) }, enabled = !state.busy) {
                        Text("解锁搜索结果的全部皮肤")
                    }
                    TextButton({ onSelect(WeaponSkinSelection()) }, enabled = !state.busy) { Text("清空皮肤页草稿") }
                } }
                items(visible, key = { "weapon-skin-${it.weapon}" }) { weapon ->
                    WeaponSkinCard(weapon, choices, state.busy, onSelect)
                }
            }
        }
        PendingEdits(state, mode, onPreview)
    }
}

@Composable private fun WeaponSkinCard(weapon: WeaponSkinState, choices: WeaponSkinSelection, busy: Boolean,
    onSelect: (WeaponSkinSelection) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun label(index: Int?): String = if (index == 0) "原皮肤" else
        weapon.skins.firstOrNull { it.index == index }?.let { "${it.label}（索引 ${it.index}）" } ?: "未核对的索引 $index（保留）"
    Panel(title = weapon.label, help = HelpTopic(weapon.label, "${HelpTopics.weaponSkins.text}\n\n武器 ID：${weapon.weapon}")) {
        if (weapon.error != null) Text(weapon.error, color = MaterialTheme.colorScheme.error)
        else {
            Text("当前：${label(weapon.current)}")
            choices.current[weapon.weapon]?.let { Text("待使用：${label(it)}", color = MaterialTheme.colorScheme.primary) }
            weapon.skins.forEach { skin ->
                val unlocked = skin.id in weapon.unlocked
                CheckLine("${skin.label} · 品质 ${skin.rarity} · 索引 ${skin.index}${if (unlocked) " · 已解锁" else ""}",
                    unlocked || skin.id in choices.unlock, !busy && !unlocked) { checked ->
                    val current = if (!checked && choices.current[weapon.weapon] == skin.index) choices.current - weapon.weapon else choices.current
                    onSelect(choices.copy(unlock = if (checked) choices.unlock + skin.id else choices.unlock - skin.id, current = current))
                }
            }
            Box {
                OutlinedButton({ menu = true }, enabled = !busy) { Text("选择当前皮肤") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("原皮肤（索引 0）") }, onClick = {
                        menu = false; onSelect(choices.copy(current = choices.current + (weapon.weapon to 0)))
                    })
                    weapon.skins.forEach { skin ->
                        val available = skin.id in weapon.unlocked || skin.id in choices.unlock
                        DropdownMenuItem(text = { Text("${skin.label} · 品质 ${skin.rarity}${if (available) "" else " · 先勾选解锁"}") }, enabled = available,
                            onClick = { menu = false; onSelect(choices.copy(current = choices.current + (weapon.weapon to skin.index))) })
                    }
                }
            }
            val highest = WeaponSkinEngine.highestAvailable(weapon, choices)
            TextButton({ onSelect(choices.copy(current = choices.current + (weapon.weapon to highest!!))) }, enabled = !busy && highest != null) {
                Text("使用可选的最高品质")
            }
            if (weapon.weapon in choices.current || weapon.skins.any { it.id in choices.unlock }) TextButton({
                onSelect(choices.copy(unlock = choices.unlock - weapon.skins.map { it.id }.toSet(), current = choices.current - weapon.weapon))
            }, enabled = !busy) { Text("撤销这把武器的皮肤草稿") }
        }
    }
}
