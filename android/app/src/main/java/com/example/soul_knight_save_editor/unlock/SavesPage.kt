package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun SavesPage(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    val free = state.mode == AssistantMode.FREE
    var manual by rememberSaveable { mutableStateOf(false) }
    var switching by rememberSaveable { mutableStateOf(false) }
    var packageDraft by rememberSaveable(state.packageName) { mutableStateOf(state.packageName) }
    val candidate = state.knownGames.firstOrNull { it.packageName == state.packageName }
    val label = if (state.packageName == GameVersions.ORIGINAL) "元气骑士 · 原版（默认）"
        else candidate?.label?.takeUnless { it == state.packageName } ?: "已保存的渠道版本"
    if (switching) AlertDialog(onDismissRequest = { switching = false },
        title = { Text("切换游戏版本") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("原版始终保留。切换后只读取所选包名，不合并不同版本的存档。", style = MaterialTheme.typography.bodySmall)
            state.knownGames.forEach { game ->
                Row(Modifier.fillMaxWidth().selectable(selected = game.packageName == state.packageName,
                    enabled = !state.busy && state.pending.isEmpty(), role = Role.RadioButton,
                    onClick = { model.packageName(game.packageName); switching = false })
                    .testTag("saved-game-${game.packageName}").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = game.packageName == state.packageName, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(if (game.packageName == GameVersions.ORIGINAL) "元气骑士 · 原版（默认）"
                            else game.label.takeUnless { it == game.packageName } ?: "自定义渠道",
                            fontWeight = FontWeight.SemiBold)
                        Text(game.packageName, style = MaterialTheme.typography.bodySmall)
                        if (game.version != "未知版本") Text(game.version, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } }, confirmButton = { TextButton({ switching = false }) { Text("关闭") } })
    LazyColumn(modifier.testTag("saves-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
        item { Panel(title = "本地存档", help = HelpTopics.saves) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(state.packageName, style = MaterialTheme.typography.bodySmall)
            candidate?.version?.takeUnless { it == "未知版本" }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton({ switching = true }, enabled = !state.busy && state.pending.isEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("switch-game")) { Text("切换游戏版本（${state.knownGames.size}）") }
            Button(if (free) model::freeScan else if (state.pinnedTarget == null) model::scan else model::refresh, enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("scan-selected-game")) {
                Text(if (free) "读取 .data 文件" else if (state.pinnedTarget == null) "读取存档" else "刷新存档")
            }
            if (!free && state.pinnedTarget != null) Row {
                Text("已记住存档位置", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(model::scan, enabled = !state.busy && state.pending.isEmpty()) { Text("重新查找") }
            }
            TextButton({ manual = !manual }, enabled = !state.busy && state.pending.isEmpty()) { Text(if (manual) "收起添加渠道 ∧" else "添加其他渠道（包名）∨") }
            AnimatedVisibility(manual) { Column {
                OutlinedTextField(packageDraft, { packageDraft = it }, label = { Text("游戏包名（高级设置）") },
                    enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("target-package"))
                TextButton({ model.packageName(packageDraft); manual = false }, enabled = !state.busy && state.pending.isEmpty() && SaveLayout.packagePattern.matches(packageDraft.trim())) { Text("保存并切换") }
            } }
        } }
        item { Status(state) }
        if (state.pending.isNotEmpty()) item { Panel {
            Text("有未完成的写回，请先恢复。", fontWeight = FontWeight.Bold)
            Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy) { Text("前往设置中的备份与恢复") }
        } }
        if (free && state.freeSnapshot != null) item { Panel {
            Text("已读取 ${state.freeSources.size} 个 .data 文件")
            Button({ model.tab(WorkspaceTabs.free) }, enabled = !state.busy) { Text("选择文件并导出") }
        } }
        (if (free) null else state.snapshot)?.let { snapshot -> item { Panel(title = "当前存档", help = HelpTopics.saves) {
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
                Text("存档格式", fontWeight = FontWeight.SemiBold)
                SaveDataModePicker(state.dataMode, !state.busy && state.pending.isEmpty(), model::dataMode)
                Text(state.dataMode?.let { if (it == SaveDataMode.MODERN)
                    "仅编辑新格式分片，保留两个读取开关。" else "仅编辑旧格式分片，已有读取开关设为 0。" }
                    ?: "请选择本次编辑的格式。", style = MaterialTheme.typography.bodySmall)
                val detected = SaveDataMode.detected(snapshot, catalog.account)
                Text("游戏读取配置：${detected?.let { "${it.label} ${it.extension}" } ?: "未能确认"}",
                    style = MaterialTheme.typography.labelSmall)
                if (detected != null && state.dataMode != null && detected != state.dataMode)
                    Text("本次编辑格式与游戏读取配置不同。选择格式不会自动切换游戏的读取方式。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                Text("首次找不到渠道时扫描全部已安装应用。找到的版本会保留，之后直接切换；默认原版始终可选。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(model::discover, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("discover-games")) { Text("扫描全部游戏版本") }
            }
            if (state.candidates.isNotEmpty() || state.discovering || state.discoveryProgress.checked > 0) Text(state.discoveryMessage, style = MaterialTheme.typography.bodySmall)
        } }
        items(state.candidates, key = { it.packageName }) { candidate -> Panel(title = candidate.label.takeUnless { it == candidate.packageName } ?: "找到的游戏版本",
            help = HelpTopic("版本信息", "${candidate.version}\n${candidate.packageName}\n${candidate.evidence.joinToString(" · ")}")) {
            Text(candidate.version, style = MaterialTheme.typography.bodySmall)
            val selected = candidate.packageName == state.packageName
            OutlinedButton({ model.packageName(candidate.packageName) }, enabled = !state.busy && state.pending.isEmpty() && !selected,
                modifier = Modifier.fillMaxWidth().testTag("select-${candidate.packageName}")) {
                Text(if (selected) "当前已选版本" else "选择这个版本")
            }
        } }
    }
}

@Composable internal fun SaveDataModePicker(selected: SaveDataMode?, enabled: Boolean, onSelect: (SaveDataMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SaveDataMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelect(mode) }, enabled = enabled,
                label = { Column {
                    Text(mode.label)
                    Text(mode.extension, style = MaterialTheme.typography.labelMedium)
                } },
                modifier = Modifier.weight(1f).testTag("save-data-mode-${mode.name.lowercase()}"))
        }
    }
}
