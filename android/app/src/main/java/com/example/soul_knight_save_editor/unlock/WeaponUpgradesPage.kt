package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun WeaponEvolutionContent(inventory: WeaponEvolutionInventory?, choices: WeaponEvolutionSelection,
    mode: AssistantMode, busy: Boolean, message: String, onSelect: (WeaponEvolutionSelection) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var count by rememberSaveable(query, selectedOnly) { mutableIntStateOf(20) }
    val visible = inventory?.states.orEmpty().filter {
        (it.definition.id.contains(query, true) || it.definition.label.contains(query, true)) &&
            (!selectedOnly || choices.allMax || it.definition.id in choices.levels)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(title = "武器进化") {
            Text(message)
            Text("已确认的普通武器完成一次进化：等级 0 → 1。保留当前皮肤和更高的已有等级，不扣除材料。",
                style = MaterialTheme.typography.bodySmall)
            if (inventory != null) Text("${inventory.states.size} 把可进化武器 · 目录外记录原样保留")
            if (mode == AssistantMode.QUICK) {
                CheckLine("完成全部已确认武器的进化", choices.allMax, !busy && inventory != null) {
                    onSelect(WeaponEvolutionSelection(allMax = it))
                }
            }
        }
        if (mode == AssistantMode.EXPERT && inventory != null) {
            Panel(title = "查找与选择") {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("武器名称或 ID") },
                    modifier = Modifier.fillMaxWidth().testTag("weapon-evolution-search"))
                CheckLine("只看本次待进化", selectedOnly, !busy) { selectedOnly = it }
                Text("${visible.size} 把匹配 · ${choices.levels.size} 把待设置")
                TextButton({ onSelect(choices.copy(levels = choices.levels + visible.filter { it.error == null && it.level == 0 }
                    .associate { it.definition.id to it.definition.maxLevel })) }, enabled = !busy) { Text("进化搜索结果") }
                TextButton({ onSelect(WeaponEvolutionSelection()) }, enabled = !busy) { Text("清空进化页草稿") }
            }
            visible.take(count).forEach { weapon ->
                val definition = weapon.definition
                key(definition.id) {
                    Panel(title = definition.label) {
                        Text(definition.id, style = MaterialTheme.typography.bodySmall)
                        if (weapon.error != null) Text(weapon.error, color = MaterialTheme.colorScheme.error)
                        else {
                            Text(if (weapon.level == 0) "当前：未进化${if (weapon.missing) "（暂无记录）" else ""}"
                                else "当前：已进化（等级 ${weapon.level}，保留）")
                            CheckLine("完成进化（等级 1）", weapon.level!! > 0 || definition.id in choices.levels,
                                !busy && weapon.level == 0) { checked ->
                                onSelect(choices.copy(levels = if (checked) choices.levels + (definition.id to definition.maxLevel)
                                    else choices.levels - definition.id))
                            }
                        }
                    }
                }
            }
            if (visible.size > count) OutlinedButton({ count += 20 }, enabled = !busy) { Text("再显示 20 把") }
        }
    }
}

@Composable internal fun MythicContent(inventory: MythicInventory?, choices: MythicSelection, mode: AssistantMode,
    busy: Boolean, message: String, onSelect: (MythicSelection) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    val visible = inventory?.states.orEmpty().filter {
        (it.definition.weapon.contains(query, true) || it.definition.label.contains(query, true) ||
            it.definition.id.toString() == query) && (!selectedOnly || choices.unlockAll || choices.maxAll ||
            it.definition.id in choices.unlock || it.definition.id in choices.levels)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(title = "神话武器") {
            Text(message)
            Text("基础解锁为 0 级，升级范围为 1 至 3 级。升级包含解锁；保留已有更高等级和目录外记录。",
                style = MaterialTheme.typography.bodySmall)
            if (inventory != null) Text("${inventory.states.size} 把已确认神话武器 · 获取次数另行设置")
            if (mode == AssistantMode.QUICK) {
                CheckLine("全部基础解锁（0 级）", choices.unlockAll, !busy && inventory != null) {
                    onSelect(choices.copy(unlockAll = it))
                }
                CheckLine("全部升至 3 级（含解锁）", choices.maxAll, !busy && inventory != null) {
                    onSelect(choices.copy(maxAll = it))
                }
            }
        }
        if (mode == AssistantMode.EXPERT && inventory != null) {
            Panel(title = "查找与选择") {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("神话武器名称或 ID") },
                    modifier = Modifier.fillMaxWidth().testTag("mythic-weapon-search"))
                CheckLine("只看本次待修改", selectedOnly, !busy) { selectedOnly = it }
                Text("${visible.size} 把匹配 · ${(choices.unlock + choices.levels.keys).size} 把待修改")
                TextButton({ onSelect(choices.copy(unlock = choices.unlock + visible.filter { it.error == null && !it.unlocked }
                    .map { it.definition.id })) }, enabled = !busy) { Text("基础解锁搜索结果") }
                TextButton({ onSelect(choices.copy(levels = choices.levels + visible.filter { it.error == null }
                    .associate { it.definition.id to it.definition.maxLevel })) }, enabled = !busy) { Text("搜索结果升至 3 级") }
                TextButton({ onSelect(MythicSelection()) }, enabled = !busy) { Text("清空神话页草稿") }
            }
            visible.forEach { weapon -> key(weapon.definition.id) { MythicCard(weapon, choices, busy, onSelect) } }
        }
    }
}

@Composable private fun MythicCard(weapon: MythicState, choices: MythicSelection, busy: Boolean, onSelect: (MythicSelection) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val definition = weapon.definition
    Panel(title = definition.label) {
        Text("${definition.weapon} · id=${definition.id}", style = MaterialTheme.typography.bodySmall)
        if (weapon.error != null) Text(weapon.error, color = MaterialTheme.colorScheme.error)
        else {
            Text(if (weapon.unlocked) "当前：等级 ${weapon.level}" else "当前：未解锁")
            val target = choices.levels[definition.id] ?: if (definition.id in choices.unlock) 0 else null
            if (target != null) Text("待设置：等级 $target${if (weapon.level!! > target) "（已有更高等级，将保留）" else ""}",
                color = MaterialTheme.colorScheme.primary)
            CheckLine("基础解锁", weapon.unlocked || definition.id in choices.unlock || definition.id in choices.levels,
                !busy && !weapon.unlocked) { checked ->
                onSelect(choices.copy(unlock = if (checked) choices.unlock + definition.id else choices.unlock - definition.id,
                    levels = if (checked) choices.levels else choices.levels - definition.id))
            }
            Box {
                OutlinedButton({ menu = true }, enabled = !busy && weapon.level!! < definition.maxLevel) { Text("选择目标等级") }
                DropdownMenu(menu, { menu = false }) {
                    (0..definition.maxLevel).forEach { level ->
                        DropdownMenuItem(text = { Text(if (level == 0) "0 级（基础解锁）" else "$level 级（含解锁）") },
                            enabled = weapon.level!! <= level, onClick = {
                                menu = false; onSelect(choices.copy(levels = choices.levels + (definition.id to level)))
                            })
                    }
                }
            }
            if (target != null) TextButton({ onSelect(choices.copy(unlock = choices.unlock - definition.id,
                levels = choices.levels - definition.id)) }, enabled = !busy) { Text("撤销这把武器的神话草稿") }
        }
    }
}
