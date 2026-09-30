package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SavesPage(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    var manual by rememberSaveable { mutableStateOf(false) }
    var packageDraft by rememberSaveable(state.packageName) { mutableStateOf(state.packageName) }
    val candidate = state.candidates.firstOrNull { it.packageName == state.packageName }
    val label = candidate?.label?.takeUnless { it == state.packageName } ?: if (state.packageName == "com.ChillyRoom.DungeonShooter") "元气骑士 · 默认版本" else "手动选择的版本"
    LazyColumn(modifier.testTag("saves-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
        item { Panel(title = "本地存档", help = HelpTopics.saves) {
            Text(label, fontWeight = FontWeight.SemiBold)
            candidate?.version?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            Button(if (state.pinnedTarget == null) model::scan else model::refresh, enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("scan-selected-game")) {
                Text(if (state.pinnedTarget == null) "读取存档" else "刷新存档")
            }
            if (state.pinnedTarget != null) Row {
                Text("已记住存档位置", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(model::scan, enabled = !state.busy && state.pending.isEmpty()) { Text("重新查找") }
            }
            TextButton({ manual = !manual }, enabled = !state.busy) { Text(if (manual) "收起手动设置 ∧" else "手动选择版本 ∨") }
            AnimatedVisibility(manual) { Column {
                OutlinedTextField(packageDraft, { packageDraft = it }, label = { Text("游戏包名（高级设置）") },
                    enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("target-package"))
                TextButton({ model.packageName(packageDraft); manual = false }, enabled = !state.busy && SaveLayout.packagePattern.matches(packageDraft.trim())) { Text("使用这个版本") }
            } }
        } }
        item { Status(state) }
        if (state.pending.isNotEmpty()) item { Panel {
            Text("有未完成的写回，请先恢复。", fontWeight = FontWeight.Bold)
            Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy) { Text("前往设置中的备份与恢复") }
        } }
        state.snapshot?.let { snapshot -> item { Panel(title = "当前存档", help = HelpTopics.saves) {
            Text("${snapshot.files.size} 份存档 · ${state.accounts.size} 个账号", style = MaterialTheme.typography.bodySmall)
            if (state.accounts.size > 1) {
                Text("选择此版本中的账号：")
                state.accounts.forEach { account ->
                    OutlinedButton({ model.account(account) }, enabled = !state.busy && state.catalog?.account != account) {
                        Text((if (account.isEmpty()) "本地账号" else "账号 …${account.takeLast(4)}") + if (state.catalog?.account == account) " · 已选" else "")
                    }
                }
            }
            state.catalog?.let { catalog ->
                Text("${catalog.heroes.size} 个角色 · ${catalog.heroes.sumOf { it.skins.size }} 个皮肤", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ model.tab(WorkspaceTabs.characters) }, enabled = !state.busy) { Text("角色功能") }
                    OutlinedButton({ model.tab(WorkspaceTabs.items) }, enabled = !state.busy) { Text("物品功能") }
                }
                OutlinedButton({ model.tab(WorkspaceTabs.weapons) }, enabled = !state.busy) { Text("武器功能") }
            }
        } } }
        item { Panel(title = "查找游戏版本", help = HelpTopics.discovery) {
            if (state.discovering) {
                val progress = state.discoveryProgress
                Text("已检查 ${progress.checked} / ${progress.total} 个应用 · 找到 ${state.candidates.size} 个候选", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(model::cancelDiscovery, modifier = Modifier.testTag("cancel-discovery")) { Text("取消搜索") }
            } else {
                OutlinedButton(model::discover, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("discover-games")) { Text("扫描") }
            }
            if (state.candidates.isNotEmpty() || state.discovering || state.discoveryProgress.checked > 0) Text(state.discoveryMessage, style = MaterialTheme.typography.bodySmall)
        } }
        items(state.candidates, key = { it.packageName }) { candidate -> Panel(title = candidate.label.takeUnless { it == candidate.packageName } ?: "找到的游戏版本",
            help = HelpTopic("版本信息", "${candidate.version}\n${candidate.packageName}\n${candidate.evidence.joinToString(" · ")}")) {
            Text(candidate.version, style = MaterialTheme.typography.bodySmall)
            val selected = candidate.packageName == state.packageName
            OutlinedButton({ model.packageName(candidate.packageName) }, enabled = !state.busy && !selected,
                modifier = Modifier.fillMaxWidth().testTag("select-${candidate.packageName}")) {
                Text(if (selected) "当前已选版本" else "选择这个版本")
            }
        } }
    }
}
