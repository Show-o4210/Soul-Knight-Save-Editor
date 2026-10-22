package com.example.soul_knight_save_editor.unlock

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun FreeEditPage(model: AssistantModel, chooseFolder: () -> Unit, chooseFiles: () -> Unit, modifier: Modifier) {
    val state = model.state
    var showIgnored by remember(state.freeReview) { mutableStateOf(false) }
    val session = state.freeSession?.takeIf { it.snapshot.packageName == state.packageName }
    var showSources by remember(session?.id) { mutableStateOf(session == null) }
    var showExports by remember(session?.id) { mutableStateOf(false) }
    LazyColumn(modifier.testTag("free-edit-page"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Panel("自由编辑") {
            Text("解码导出 → 选择外部应用编辑 → 导入并打包", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("仅检查格式与编码，不修正字段、镜像或分片关系。自行修改造成的游戏内异常由用户承担；写入前会自动备份。", style = MaterialTheme.typography.bodySmall)
            Text(state.packageName, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.tab(WorkspaceTabs.saves) }, enabled = !state.busy) { Text("切换游戏版本") }
                OutlinedButton(model::freeScan, enabled = !state.busy && state.pending.isEmpty()) { Text("读取文件") }
            }
        } }
        item { Status(state) }
        if (state.pending.isNotEmpty()) item { Panel {
            Text("有未完成事务，请先恢复。")
            Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy) { Text("备份与恢复") }
        } }
        item { Panel("导出位置") {
            Text(state.freeFolder?.let { BackupDestination.label(Uri.parse(it)) } ?: "请选择一个供外部应用编辑的本地目录。")
            OutlinedButton(chooseFolder, enabled = !state.busy) { Text(if (state.freeFolder == null) "选择导出目录" else "更换导出目录") }
            Text("每次导出创建独立子目录，随后弹出应用选择器。可选择文件管理器或逐文件选择编辑应用。", style = MaterialTheme.typography.bodySmall)
            if (state.freeAccounts.size > 1) {
                Text("无账号文件名的分片，使用以下账号的本地读取开关：", style = MaterialTheme.typography.bodySmall)
                state.freeAccounts.forEach { account ->
                    Row {
                        RadioButton(state.freeAccount == account, { model.freeAccount(account) }, enabled = !state.busy)
                        Text(if (account.isEmpty()) "默认账号" else "账号 …${account.takeLast(4)}", Modifier.padding(top = 12.dp))
                    }
                }
            }
            if (state.freeSources.isNotEmpty()) {
                TextButton({ showSources = !showSources }) { Text(if (showSources) "收起待导出文件" else "选择下一次导出文件") }
                TextButton({ state.freeSources.forEach { model.freeSelect(it.path, true) } }, enabled = !state.busy) { Text("全选 .data 文件") }
                TextButton({ state.freeSources.forEach { model.freeSelect(it.path, false) } }, enabled = !state.busy) { Text("清空选择") }
                Button(model::freeExport, enabled = !state.busy && state.pending.isEmpty() && state.freeFolder != null && state.freeSelected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("free-export")) { Text("解码并导出 ${state.freeSelected.size} 个文件") }
            }
        } }
        if (showSources) items(state.freeSources, key = { "source:${it.path}" }) { source -> Panel {
            CheckLine(source.path.substringAfter("/files/"), source.path in state.freeSelected, !state.busy) { model.freeSelect(source.path, it) }
            Text(source.codec.label, style = MaterialTheme.typography.bodySmall)
        } }
        if (session == null && model.canResumeFree()) item { Panel {
            OutlinedButton(model::freeResume, enabled = !state.busy) { Text("继续上次导出") }
        } }
        if (session != null) {
            item { Panel("外部编辑与导入") {
                Text(runCatching { DocumentsContract.getDocumentId(Uri.parse(session.directory)).replace("primary:", "内部存储 / ") }.getOrDefault("上次导出目录"), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(model::freeOpenDirectory, enabled = !state.busy, modifier = Modifier.testTag("free-open-directory")) { Text("打开目录（选择应用）") }
                Text("编辑完成后再导入。多余文件先比较哈希，再按文件名前缀匹配；多个不同修改版本需自行选择。未匹配的文件会保留在原目录，不写入游戏。", style = MaterialTheme.typography.bodySmall)
                Button({ model.freeImport() }, enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("free-import-directory")) { Text("从导出目录导入") }
                OutlinedButton(chooseFiles, enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("选择文件导入") }
                TextButton({ showExports = !showExports }) { Text(if (showExports) "收起导出文件" else "逐文件打开（${session.entries.size} 个）") }
            } }
            if (showExports) items(session.entries, key = { "export:${it.path}" }) { entry -> Panel {
                Text(entry.name, fontWeight = FontWeight.SemiBold)
                OutlinedButton({ model.freeOpenFile(entry) }, enabled = !state.busy) { Text("打开文件（选择应用）") }
            } }
        }
        state.freeReview?.let { review ->
            item { Panel("导入比较") {
                Text("${review.changed} 份内容变化 · ${review.unresolved} 份待选择 · ${review.ignored.size} 个文件忽略")
                if (review.ignored.isNotEmpty()) {
                    TextButton({ showIgnored = !showIgnored }) { Text(if (showIgnored) "收起忽略文件" else "查看忽略文件") }
                    if (showIgnored) Text(review.ignored.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            } }
            items(review.matches, key = { "match:${it.entry.path}" }) { match -> Panel {
                Text(match.entry.name, fontWeight = FontWeight.SemiBold)
                Text(when {
                    match.selected == FreeImportReview.SKIP -> "本次不导入"
                    match.candidates.isEmpty() -> "没有匹配文件，保持原存档"
                    match.selected == null -> "发现多个不同的修改版本，请选择一份"
                    match.chosen!!.document.hash == match.entry.plainHash -> "与导出内容相同，无需写入"
                    else -> "已选：${match.chosen!!.document.name} · ${match.chosen!!.matchedBy}匹配"
                }, style = MaterialTheme.typography.bodySmall)
                if (match.candidates.size > 1 || match.selected == FreeImportReview.SKIP) match.candidates.forEach { candidate ->
                    Row {
                        RadioButton(match.selected == candidate.document.id, { model.freeChoose(match.entry.path, candidate.document.id) }, enabled = !state.busy)
                        Text("${candidate.document.name}\n${candidate.matchedBy} · ${candidate.document.hash.take(12)}", Modifier.weight(1f).padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (match.candidates.isNotEmpty()) TextButton({ model.freeChoose(match.entry.path, FreeImportReview.SKIP) }, enabled = !state.busy) { Text("本次不导入此文件") }
            } }
            item {
                if (!state.personalUseAccepted) OutlinedButton(model::requestConsent) { Text("确认个人使用约定") }
                Button(model::freePreview, enabled = !state.busy && state.pending.isEmpty() && state.personalUseAccepted && review.unresolved == 0 && review.changed > 0,
                    modifier = Modifier.fillMaxWidth().testTag("free-preview")) { Text("预览重新打包与写回") }
            }
        }
    }
}
