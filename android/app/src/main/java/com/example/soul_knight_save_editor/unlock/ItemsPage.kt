package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

private fun ItemChoices.isSelected(key: ItemKey) = key in marked || key in actions.selected ||
    !amountText[key].isNullOrBlank() || !incrementText[key].isNullOrBlank()

private fun itemValue(root: JsonObject, entry: ItemDefinition): String = runCatching {
    when (entry.kind) {
        ItemKind.QUANTITY, ItemKind.AMOUNT, ItemKind.TAPE -> ItemEngine.quantity(root, entry.key).toString()
        ItemKind.BLUEPRINT -> (root[entry.key.field] as? JsonObject)?.get(entry.key.id)?.toString() ?: "尚无记录"
        ItemKind.FACILITY -> if ((root["itemUnlock"] as? JsonArray)?.contains(JsonPrimitive(entry.key.id)) == true) "已加入" else "尚无记录"
        ItemKind.JEWELRY -> (root["jewelryData"] as? JsonObject)?.get(entry.key.id)?.jsonObject?.get("durability")?.toString() ?: "尚无记录"
    }
}.getOrDefault("格式未知")

@Composable internal fun ItemsPage(model: AssistantModel, mode: AssistantMode, modifier: Modifier) {
    val state = model.state
    val root = state.itemRoot
    val choices = state.itemsFor(mode)
    fun update(next: ItemChoices) = if (mode == AssistantMode.QUICK) model.items(next) else model.expertItems(next)
    var query by rememberSaveable(state.packageName, state.catalog?.account) { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("全部") }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var batch by rememberSaveable(state.packageName, state.catalog?.account) { mutableStateOf("") }
    val visible = ItemCatalog.entries.filter { (group == "全部" || it.group == group) &&
        (it.label.contains(query, true) || it.key.id.contains(query, true)) && (!onlySelected || choices.isSelected(it.key)) }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel(title = "物品", help = HelpTopics.items) {
                Text(state.itemMessage)
                if (state.catalog == null) OutlinedButton({ model.tab(WorkspaceTabs.saves) }, enabled = !state.busy) { Text("前往存档扫描") }
                if (!state.personalUseAccepted) TextButton(model::requestConsent) { Text("先确认个人使用约定") }
            } }
            item { Status(state) }
            if (root != null && mode == AssistantMode.QUICK) {
                item { Panel(title = "快捷操作", help = HelpTopics.items) {
                    val actions = choices.actions
                    CheckLine("磁带全部获取至 1", actions.tapes, !state.busy) { update(choices.copy(actions = actions.copy(tapes = it))) }
                    CheckLine("蓝图全部研究", actions.blueprints, !state.busy) { update(choices.copy(actions = actions.copy(blueprints = it))) }
                    CheckLine("补齐设施列表", actions.facilities, !state.busy) { update(choices.copy(actions = actions.copy(facilities = it))) }
                    CheckLine("饰品补齐并充能", actions.jewelry, !state.busy) { update(choices.copy(actions = actions.copy(jewelry = it))) }
                } }
                items(ItemDrafts.groups.entries.toList(), key = { "group-${it.key}" }) { (name, entries) -> Panel(title = "$name · ${entries.size} 项", help = HelpTopics.quantity) {
                    val delta = choices.actions.increments[entries.first().key] ?: 0
                    Text("每项待增加 $delta")
                    Row {
                        Button({ model.itemGroup(name) }, enabled = !state.busy) { Text("每项 +1000") }
                        TextButton({ model.itemGroup(name, true) }, enabled = !state.busy && delta >= 1000) { Text("撤销一次") }
                    }
                } }
                items(ItemCatalog.ofKind(ItemKind.AMOUNT), key = { it.key.id }) { entry -> Panel(title = entry.label, help = HelpTopic(entry.label, "${HelpTopics.quantity.text}\n\n物品 ID：${entry.key.field}.${entry.key.id}")) {
                    Text("当前 ${itemValue(root, entry)}")
                    NumberInput(choices.amountText[entry.key].orEmpty(), "目标数量（留空不改）", !state.busy) {
                        update(choices.copy(amountText = choices.amountText + (entry.key to it)))
                    }
                } }
            }
            if (root != null && mode == AssistantMode.EXPERT) {
                item { Panel(title = "逐项选择", help = HelpTopics.expert) {
                    OutlinedTextField(query, { query = it }, label = { Text("中文名称或内部 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf("全部") + ItemCatalog.entries.map { it.group }.distinct()).forEach { name ->
                            FilterChip(group == name, { group = name }, label = { Text(name) })
                        }
                    }
                    CheckLine("只看已选／已填参数", onlySelected, true) { onlySelected = it }
                    Text("${visible.size} 项匹配 · ${choices.actions.selected.size} 项解锁 · ${choices.marked.size} 项批量数量选择")
                    Row {
                        TextButton({
                            val numeric = visible.filter { it.kind in setOf(ItemKind.QUANTITY, ItemKind.AMOUNT) }.map { it.key }
                            val unlocks = visible.filter { it.kind !in setOf(ItemKind.QUANTITY, ItemKind.AMOUNT) }.map { it.key }
                            update(choices.copy(marked = choices.marked + numeric, actions = choices.actions.copy(selected = choices.actions.selected + unlocks)))
                        }, enabled = !state.busy) { Text("选择当前结果") }
                        TextButton({ update(ItemChoices()) }, enabled = !state.busy) { Text("清空物品页草稿") }
                    }
                    NumberInput(batch, "批量参数（仅已勾选的数量条目）", !state.busy) { batch = it }
                    Row {
                        TextButton({
                            val amount = requireNotNull(ItemChoices.parseAmount(batch))
                            update(choices.copy(amountText = choices.amountText + choices.marked.associateWith { amount.toString() },
                                incrementText = choices.incrementText - choices.marked))
                        }, enabled = !state.busy && choices.marked.isNotEmpty() && ItemChoices.parseAmount(batch) != null) { Text("设置目标数量") }
                        TextButton({
                            val delta = requireNotNull(ItemChoices.parseAmount(batch))
                            update(choices.copy(incrementText = choices.incrementText + choices.marked.associateWith { delta.toString() },
                                amountText = choices.amountText - choices.marked))
                        }, enabled = !state.busy && choices.marked.isNotEmpty() && choices.marked.all { ItemCatalog.byKey[it]?.kind == ItemKind.QUANTITY } && ItemChoices.parseAmount(batch) != null) { Text("设置增加量") }
                    }
                } }
                items(visible, key = { "${it.key.field}/${it.key.id}" }) { entry -> Panel(title = entry.label, help = HelpTopic(entry.label, "${if (entry.kind in setOf(ItemKind.QUANTITY, ItemKind.AMOUNT)) HelpTopics.quantity.text else HelpTopics.items.text}\n\n物品 ID：${entry.key.field}.${entry.key.id}")) {
                    Text("当前 ${itemValue(root, entry)}", style = MaterialTheme.typography.labelSmall)
                    val numeric = entry.kind in setOf(ItemKind.QUANTITY, ItemKind.AMOUNT)
                    if (numeric) {
                        CheckLine("纳入批量数量操作", entry.key in choices.marked, !state.busy) {
                            update(choices.copy(marked = if (it) choices.marked + entry.key else choices.marked - entry.key))
                        }
                        NumberInput(choices.amountText[entry.key].orEmpty(), "目标数量", !state.busy) {
                            update(choices.copy(amountText = choices.amountText + (entry.key to it), incrementText = choices.incrementText - entry.key))
                        }
                        if (entry.kind == ItemKind.QUANTITY) NumberInput(choices.incrementText[entry.key].orEmpty(), "增加量", !state.busy) {
                            update(choices.copy(incrementText = choices.incrementText + (entry.key to it), amountText = choices.amountText - entry.key))
                        }
                    } else CheckLine(when (entry.kind) {
                        ItemKind.TAPE -> "获取至 1"; ItemKind.BLUEPRINT -> "完成研究"
                        ItemKind.FACILITY -> "补入设施列表"; else -> "补齐／充能至 100"
                    }, entry.key in choices.actions.selected, !state.busy) {
                        update(choices.copy(actions = choices.actions.copy(selected = if (it) choices.actions.selected + entry.key else choices.actions.selected - entry.key)))
                    }
                    if (choices.isSelected(entry.key)) TextButton({ update(choices.copy(marked = choices.marked - entry.key,
                        actions = choices.actions.copy(selected = choices.actions.selected - entry.key),
                        amountText = choices.amountText - entry.key, incrementText = choices.incrementText - entry.key)) }, enabled = !state.busy) { Text("撤销该项草稿") }
                } }
            }
        }
        PendingEdits(state, mode, model::preview)
    }
}

@Composable internal fun NumberInput(text: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(text, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = text.isNotBlank() && ItemChoices.parseAmount(text) == null,
        supportingText = { if (text.isNotBlank() && ItemChoices.parseAmount(text) == null) Text("请输入 0 至 2147483647 的整数") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
