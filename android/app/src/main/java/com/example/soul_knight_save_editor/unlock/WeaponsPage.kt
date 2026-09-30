package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun WeaponsPage(model: AssistantModel, mode: AssistantMode, modifier: Modifier) {
    WeaponContent(model.state, mode, modifier, { model.tab(WorkspaceTabs.saves) }, model::weapons,
        model::preview, model::requestConsent, { model.page(AssistantPage.BACKUPS) }, model::expertWeapons)
}

@Composable internal fun WeaponContent(state: AssistantState, mode: AssistantMode, modifier: Modifier = Modifier,
    onSaves: () -> Unit, onSelect: (Boolean) -> Unit, onPreview: () -> Unit, onConsent: () -> Unit, onBackups: () -> Unit,
    onExpert: (WeaponChoices) -> Unit = {}) {
    var query by rememberSaveable(state.packageName, state.catalog?.account) { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var batch by rememberSaveable(state.packageName, state.catalog?.account) { mutableStateOf("") }
    val choices = state.expertWeapons
    val visible = state.weaponStates.filter { (it.id.contains(query, true) || WeaponCatalog.names[it.id].orEmpty().contains(query, true)) &&
        (!selectedOnly || it.id in choices.marked || !choices.incrementText[it.id].isNullOrBlank()) }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).testTag("weapons-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel(title = "武器", help = HelpTopics.weapons) {
                if (state.catalog == null) {
                    Text("请先读取存档。")
                    OutlinedButton(onSaves, enabled = !state.busy) { Text("前往存档") }
                }
            } }
            item { Status(state) }
            if (state.pending.isNotEmpty()) item { Panel {
                Text("有未完成的写回，请先恢复", fontWeight = FontWeight.Bold)
                Button(onBackups, enabled = !state.busy) { Text("前往备份与恢复") }
            } }
            if (state.catalog != null && mode == AssistantMode.QUICK) item { Panel(title = "获取次数", help = HelpTopics.weapons) {
                Text(state.weaponMessage, style = MaterialTheme.typography.bodySmall)
                CheckLine("全部武器获取次数 +8", state.choices.weapons(mode), !state.busy && state.weaponInfo != null, onSelect)
                if (!state.personalUseAccepted) TextButton(onConsent) { Text("先确认个人使用约定") }
            } }
            if (state.catalog != null && mode == AssistantMode.EXPERT) {
                item { Panel(title = "逐项选择", help = HelpTopics.expert) {
                    Text(state.weaponMessage)
                    OutlinedTextField(query, { query = it }, label = { Text("武器中文名称或 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CheckLine("只看已选／已填增量", selectedOnly, true) { selectedOnly = it }
                    Text("${visible.size} 项匹配 · ${choices.marked.size} 项批量选择 · ${choices.incrementText.count { ItemChoices.parseAmount(it.value)?.let { n -> n > 0 } == true }} 项待增加")
                    Row {
                        TextButton({ onExpert(choices.copy(marked = choices.marked + visible.filter { it.count != null }.map { it.id })) }, enabled = !state.busy) { Text("选择搜索结果") }
                        TextButton({ onExpert(WeaponChoices()) }, enabled = !state.busy) { Text("清空武器页草稿") }
                    }
                    NumberInput(batch, "已选武器的增加量", !state.busy) { batch = it }
                    Row {
                        TextButton({ onExpert(choices.copy(incrementText = choices.incrementText + choices.marked.associateWith { "8" })) }, enabled = !state.busy && choices.marked.isNotEmpty()) { Text("设为 +8") }
                        TextButton({ onExpert(choices.copy(incrementText = choices.incrementText + choices.marked.associateWith { batch })) }, enabled = !state.busy && choices.marked.isNotEmpty() && ItemChoices.parseAmount(batch) != null) { Text("使用自定义增量") }
                    }
                    if (!state.personalUseAccepted) TextButton(onConsent) { Text("先确认个人使用约定") }
                } }
                items(visible, key = { it.id }) { weapon -> Panel(title = WeaponCatalog.names[weapon.id] ?: weapon.id, help = HelpTopic("武器获取次数", "${HelpTopics.weapons.text}\n\n武器 ID：${weapon.id}")) {
                    Text(weapon.error ?: "当前 ${weapon.count}${if (weapon.missing) "（尚未获取）" else ""}")
                    CheckLine("纳入批量增量", weapon.id in choices.marked, !state.busy && weapon.count != null) {
                        onExpert(choices.copy(marked = if (it) choices.marked + weapon.id else choices.marked - weapon.id))
                    }
                    NumberInput(choices.incrementText[weapon.id].orEmpty(), "本次增加量", !state.busy && weapon.count != null) {
                        onExpert(choices.copy(incrementText = choices.incrementText + (weapon.id to it)))
                    }
                    if (!choices.incrementText[weapon.id].isNullOrBlank() || weapon.id in choices.marked)
                        TextButton({ onExpert(choices.copy(marked = choices.marked - weapon.id, incrementText = choices.incrementText - weapon.id)) }, enabled = !state.busy) { Text("撤销该项草稿") }
                } }
            }
        }
        PendingEdits(state, mode, onPreview)
    }
}
