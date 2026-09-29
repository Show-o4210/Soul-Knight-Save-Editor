package com.example.soul_knight_save_editor.unlock

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.soul_knight_save_editor.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val quickColors = lightColorScheme(primary = Color(0xFF66509C), onPrimary = Color.White,
    background = Color(0xFFF5EFFC), surface = Color(0xFFFFFBFF), surfaceVariant = Color(0xFFECE3F6),
    onSurface = Color(0xFF2C223B), onSurfaceVariant = Color(0xFF655A75), outlineVariant = Color(0xFFD7CCE6))
private val expertColors = darkColorScheme(primary = Color(0xFFD5BFFF), onPrimary = Color(0xFF302047),
    background = Color(0xFF1D1330), surface = Color(0xFF2B2041), surfaceVariant = Color(0xFF3C2E56),
    onSurface = Color(0xFFF6EEFF), onSurfaceVariant = Color(0xFFCEBEDF), outlineVariant = Color(0xFF65527E))
private val guideColors = darkColorScheme(primary = Color(0xFFE1D0FB), onPrimary = Color(0xFF38264F),
    background = Color(0xFF675A7B), surface = Color(0xFF423550),
    onSurface = Color(0xFFFFF8FF), onSurfaceVariant = Color(0xFFE8DBF3), outlineVariant = Color(0xFFA995BF))
private val names = mapOf("Knight" to "骑士", "Ranger" to "游侠", "Mage" to "法师", "Assassin" to "刺客",
    "Alchemist" to "炼金术士", "Engineer" to "工程师", "Vampire" to "吸血鬼", "Paladin" to "圣骑士",
    "Priest" to "牧师", "Robot" to "机器人", "LadyChef" to "厨娘")
private fun Hero.label() = "${names[name] ?: name} · c$index"
private fun accountLabel(value: String) = if (value.isEmpty()) "本地账号" else "账号 …${value.takeLast(4)}"
private fun timestamp(value: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))

@Composable fun AssistantApp(model: AssistantModel, chooseFolder: () -> Unit, openFolder: () -> Unit) {
    val state = model.state
    val view = LocalView.current
    SideEffect { (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = state.mode == AssistantMode.QUICK } }
    BackHandler(state.mode != null && state.preview == null) {
        if (state.busy) model.notice("操作进行中，请等待完成")
        else when (state.page) {
            AssistantPage.WORKSPACE -> model.guide()
            AssistantPage.BACKUPS -> model.page(AssistantPage.WORKSPACE)
            AssistantPage.SETTINGS -> model.page(if (state.mode == AssistantMode.QUICK) state.settingsReturnPage else AssistantPage.WORKSPACE)
        }
    }
    Crossfade(targetState = state.mode to state.page, animationSpec = tween(260), label = "screen-fade") { (mode, page) ->
        val palette = when (mode) { null -> guideColors; AssistantMode.QUICK -> quickColors; AssistantMode.EXPERT -> expertColors }
        MaterialTheme(colorScheme = palette) {
            Box(Modifier.fillMaxSize()) {
                Image(painterResource(if (mode == AssistantMode.QUICK) R.drawable.background_quick else R.drawable.background_expert), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                if (mode == null) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    listOf(Color(0xFF837397).copy(alpha = .86f), Color(0xFF5D506E).copy(alpha = .88f)))))
                else Box(Modifier.matchParentSize().background(palette.background.copy(alpha = if (mode == AssistantMode.EXPERT) .60f else .68f)))
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp)) {
                    Header(mode, page, state.busy, model)
                    if (mode == null) Guide(model, Modifier.weight(1f))
                    else when (page) {
                        AssistantPage.WORKSPACE -> Workspace(model, mode, Modifier.weight(1f))
                        AssistantPage.BACKUPS -> Backups(model, chooseFolder, openFolder, Modifier.weight(1f))
                        AssistantPage.SETTINGS -> Settings(model, Modifier.weight(1f))
                    }
                    if (mode == AssistantMode.QUICK && page != AssistantPage.SETTINGS) QuickTabs(state, model)
                }
            }
        }
    }
    MaterialTheme(colorScheme = when (state.mode) { null -> guideColors; AssistantMode.QUICK -> quickColors; AssistantMode.EXPERT -> expertColors }) {
        Preview(model)
        if (state.consentPromptVisible) AlertDialog(onDismissRequest = model::declinePersonalUse,
            title = { Text("个人本地存档使用确认") },
            text = { Text("本工具只应处理你有权访问的个人手机或虚拟机上的本地存档。修改前请查看预览并保留备份；修改结果及使用后果由你自行确认。确认后才可使用写入功能。") },
            confirmButton = { Button(model::acceptPersonalUse) { Text("我已了解并同意") } },
            dismissButton = { TextButton(model::declinePersonalUse) { Text("暂不使用写入") } })
    }
}

@Composable private fun Header(mode: AssistantMode?, page: AssistantPage, busy: Boolean, model: AssistantModel) {
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (mode == null) .70f else if (mode == AssistantMode.EXPERT) .82f else .80f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary) {
                Text("SK", Modifier.padding(12.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("骑士档案馆", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (mode == null) "先选模式 · 随时可切换" else "本地存档助手 · 离线运行",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mode != null) {
                if (mode == AssistantMode.QUICK) {
                    if (page == AssistantPage.SETTINGS) TextButton({ model.page(model.state.settingsReturnPage) }, enabled = !busy) { Text("返回") }
                    else {
                        TextButton(model::guide, enabled = !busy) { Text("模式") }
                        TextButton({ model.page(AssistantPage.SETTINGS) }, enabled = !busy) { Text("设置") }
                    }
                } else if (page == AssistantPage.WORKSPACE) {
                    TextButton({ model.page(AssistantPage.BACKUPS) }, enabled = !busy) { Text("备份") }
                    TextButton({ model.page(AssistantPage.SETTINGS) }, enabled = !busy) { Text("设置") }
                } else TextButton({ model.page(AssistantPage.WORKSPACE) }, enabled = !busy) { Text("返回") }
            }
        }
    }
}

@Composable private fun QuickTabs(state: AssistantState, model: AssistantModel) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(AssistantPage.WORKSPACE to "一键解锁", AssistantPage.BACKUPS to "备份与恢复").forEach { (target, label) ->
                val selected = state.page == target
                Surface(onClick = { model.page(target) }, enabled = !state.busy && state.mode == AssistantMode.QUICK,
                    modifier = Modifier.weight(1f).testTag(if (target == AssistantPage.WORKSPACE) "quick-tab-unlock" else "quick-tab-backup"),
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Box(Modifier.heightIn(min = 54.dp), contentAlignment = Alignment.Center) {
                        Text(label + if (target == AssistantPage.BACKUPS && state.pending.isNotEmpty()) " •" else "",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable private fun Guide(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    Column(modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard("快捷解锁", "一键处理已有内容", "选择角色、皮肤、等级或技能；预览后再应用。", false,
            Modifier.weight(1f).fillMaxWidth()) { model.chooseMode(AssistantMode.QUICK) }
        ModeCard("详细选择", "按角色与皮肤逐项选择", "适合想精确控制解锁范围的玩家。", true,
            Modifier.weight(1f).fillMaxWidth()) { model.chooseMode(AssistantMode.EXPERT) }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .78f),
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                CheckLine("记住选择，下次直接进入", state.rememberMode, true, model::rememberMode)
                Text("同一套安全检查 · 只处理自己的本地存档", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable private fun ModeCard(title: String, caption: String, description: String, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick, shape = RoundedCornerShape(24.dp), modifier = modifier.testTag(if (dark) "choose-expert" else "choose-quick")) {
        Box(Modifier.fillMaxSize()) {
            Image(painterResource(if (dark) R.drawable.background_expert else R.drawable.background_quick), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background((if (dark) Color(0xFF25153F) else Color(0xFFF8F1FF)).copy(alpha = .77f)))
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = if (dark) Color(0xFFD8C5EF) else Color(0xFF6A4F94))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = if (dark) Color.White else Color(0xFF332346))
                    Text(description, color = if (dark) Color(0xFFE3D5F2) else Color(0xFF5A486F), style = MaterialTheme.typography.bodyMedium)
                }
                Text("点击进入  →", color = if (dark) Color.White else Color(0xFF513973), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable private fun Workspace(model: AssistantModel, renderMode: AssistantMode, modifier: Modifier) {
    val state = model.state
    val catalog = state.catalog
    val quick = renderMode == AssistantMode.QUICK
    var query by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        if (!quick) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(model::guide, enabled = !state.busy && state.mode == renderMode, modifier = Modifier.testTag("return-mode-selection")) { Text("← 选择模式") }
            Spacer(Modifier.weight(1f))
            Text("详细选择", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel {
                Text(if (catalog == null) "从你的存档开始" else "本地存档已就绪", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (catalog == null) "扫描仅访问所选游戏的本地目录。" else "${accountLabel(catalog.account)} · ${catalog.heroes.size} 个角色 · ${catalog.heroes.sumOf { it.skins.size }} 个皮肤", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(model::scan, enabled = !state.busy && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (catalog == null) "Root 扫描存档" else "重新扫描") }
                Text("扫描会关闭游戏；操作结束后你可以自行打开。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (catalog != null && quick) item { Panel {
                Text("这次想修改什么？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CheckLine("全部角色解锁", state.choices.quickRoles, !state.busy) { model.quick(roles = it) }
                CheckLine("全部皮肤解锁", state.choices.quickSkins, !state.busy) { model.quick(skins = it) }
                CheckLine("角色等级至少 7 级", state.choices.quickLevels, !state.busy && !catalog.xmlOnly) { model.quick(levels = it) }
                CheckLine("解锁已有技能条目", state.choices.quickSkills, !state.busy && !catalog.xmlOnly) { model.quick(skills = it) }
                Text(if (catalog.xmlOnly) "当前只有 XML；等级与技能需 game.data 双文件核对。" else "四项默认不选。高于 7 级的角色保持原等级；只处理现有技能条目。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.personalUseAccepted) TextButton(model::requestConsent) { Text("先确认个人使用约定") }
            } }
            item { Status(state) }
            if (state.pending.isNotEmpty()) item { Panel {
                Text("有未完成的写回，请先恢复", fontWeight = FontWeight.Bold)
                Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy) { Text("前往备份与恢复") }
            } }
            if (!quick && state.accounts.size > 1) item { Panel {
                Text("选择账号")
                state.accounts.forEach { account -> OutlinedButton({ model.account(account) }, enabled = !state.busy) { Text(accountLabel(account)) } }
            } }
            if (catalog != null && !quick) {
                item { Panel {
                    Text("只选择你需要的内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(query, { query = it }, label = { Text("名称、内部名或 c 编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("皮肤编号不是游戏界面的排列顺序。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                val heroes = catalog.heroes.filter { it.label().contains(query, true) || it.name.contains(query, true) }
                if (heroes.isEmpty()) item { Panel { Text("没有匹配条目，试试角色内部名或编号。") } }
                items(heroes, key = { "hero-${it.index}" }) { hero -> HeroCard(hero, state, model) }
            }
            item { Text(if (quick) "底部切换备份与恢复；渠道设置在顶部。" else "备份与渠道设置在顶部入口，两种模式都能使用。",
                Modifier.padding(6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (catalog != null) {
            val selected = state.choices.selection(renderMode, catalog)
            Button(model::preview, enabled = !state.busy && state.personalUseAccepted && state.mode == renderMode &&
                (selected.heroes.isNotEmpty() || selected.skins.isNotEmpty() || selected.levels || selected.skills),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 52.dp)) {
                Text(if (quick) "预览本次修改" else "预览所选 · ${selected.heroes.size} 角色 / ${selected.skins.size} 皮肤")
            }
        }
    }
}

@Composable private fun HeroCard(hero: Hero, state: AssistantState, model: AssistantModel) {
    var expanded by rememberSaveable(hero.index) { mutableStateOf(false) }
    val selection = state.choices.expert
    Panel {
        Text(hero.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${hero.skins.count { it.value == 1 }}/${hero.skins.size} 皮肤已解锁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hero.unlocked != null) CheckLine(if (hero.unlocked) "角色已解锁" else "解锁角色", hero.unlocked || hero.index in selection.heroes, !state.busy && !hero.unlocked) {
            model.select(selection.copy(heroes = if (it) selection.heroes + hero.index else selection.heroes - hero.index))
        }
        Row {
            TextButton({ model.select(selection.copy(skins = selection.skins + hero.skins.filterValues { it != 1 }.keys.map { SkinId(hero.index, it) })) }, enabled = !state.busy) { Text("选择未解锁皮肤") }
            TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
        }
        if (expanded) hero.skins.toSortedMap().forEach { (id, value) ->
            val skin = SkinId(hero.index, id)
            CheckLine("皮肤 #$id · ${if (value == 1) "已解锁" else "原值 $value"}", value == 1 || skin in selection.skins, !state.busy && value != 1) {
                model.select(selection.copy(skins = if (it) selection.skins + skin else selection.skins - skin))
            }
        }
    }
}

@Composable private fun Backups(model: AssistantModel, chooseFolder: () -> Unit, openFolder: () -> Unit, modifier: Modifier) {
    val state = model.state
    var restoreId by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Panel {
            Text("备份与恢复", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("把原件留在自己手里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("当前存档备份包含本地 .data 分片与识别到的 XML，不含 .data.new 或其他账号配置。备份含个人存档，请勿公开分享。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("备份文件夹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.folder?.let { BackupDestination.label(Uri.parse(it)) } ?: "尚未选择。建议在“文档”中建立专用文件夹。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(chooseFolder, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.folder == null) "选择文件夹" else "更换目录") }
                OutlinedButton(openFolder, enabled = !state.busy && state.folder != null, modifier = Modifier.weight(1f)) { Text("打开文件夹") }
            }
            CheckLine("导出成功后自动打开文件夹", state.autoOpenFolder, !state.busy, model::autoOpen)
            Text("通过系统目录界面查看备份，按返回即可；不会导入或恢复文件。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(model::backupNow, enabled = !state.busy && state.folder != null && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("备份当前本地存档") }
            Text("会关闭游戏并读取原件，不修改任何游戏文件。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Status(state) }
        items(state.pending, key = { "pending-$it" }) { id -> Panel {
            Text("未完成的写回", fontWeight = FontWeight.Bold)
            Text(timestamp(id.substringBefore('-').toLongOrNull() ?: 0))
            Button({ model.restore(id, true) }, enabled = !state.busy) { Text("恢复未完成事务") }
        } }
        if (state.archives.isNotEmpty()) item { Section("已生成的备份包", "应用内还保留一份副本；外部目录中的文件不受卸载助手影响。") }
        items(state.archives, key = { it.file.name }) { info -> Panel {
            Text(if (info.kind == "local-save") "当前本地存档" else "修改前原件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${timestamp(info.createdAt)} · ${info.count} 个文件 · ${info.file.length() / 1024} KB", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(info.packageName, style = MaterialTheme.typography.labelSmall)
            OutlinedButton({ model.exportArchive(info) }, enabled = !state.busy && state.folder != null) { Text("导出此备份包") }
        } }
        if (state.originalBackups.isNotEmpty()) item { Section("修改前原件", "仅包含当次实际改动文件的原件，不等同于全部本地分片。") }
        items(state.originalBackups, key = { "original-$it" }) { id -> Panel {
            Text(timestamp(id.substringBefore('-').toLongOrNull() ?: 0), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.exportOriginal(id) }, enabled = !state.busy && state.folder != null) { Text("导出原件") }
                if (id in state.backups) TextButton({ restoreId = id }, enabled = !state.busy && state.pending.isEmpty()) { Text("恢复原件") }
            }
        } }
        item { Panel {
            Text("关于恢复", fontWeight = FontWeight.Bold)
            Text("当前只恢复助手自己的写前备份。游戏已有新变化时会停止覆盖。暂不提供 ZIP 导入或跨账号复制。", style = MaterialTheme.typography.bodySmall)
            Text("未导出的应用内备份会随助手卸载而丢失。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
    restoreId?.let { id -> AlertDialog(onDismissRequest = { restoreId = null }, title = { Text("恢复修改前原件？") },
        text = { Text("会关闭游戏并核对文件。若游戏已产生后续变化，将拒绝覆盖，保留备份。") },
        confirmButton = { Button({ restoreId = null; model.restore(id, false) }) { Text("确认恢复") } }, dismissButton = { TextButton({ restoreId = null }) { Text("取消") } }) }
}

@Composable private fun Settings(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Panel {
            Text("公共设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("不必进入详细模式，也能配置自己的渠道。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(state.packageName, model::packageName, label = { Text("游戏渠道包名") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("更换包名后会清空扫描结果与待执行选择。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("启动与模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            CheckLine("记住上次使用的模式", state.rememberMode, !state.busy, model::rememberMode)
            OutlinedButton(model::guide, enabled = !state.busy) { Text("重新查看入口引导") }
        } }
        item { Panel {
            Text("共同的边界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("两种模式使用相同的扫描、备份和写回校验。快捷模式可选择将现有角色提升至至少 7 级、解锁已有技能；详细模式仍只处理角色与皮肤。不操作 .data.new、货币及游玩记录。")
            if (!state.personalUseAccepted) OutlinedButton(model::requestConsent) { Text("确认个人使用约定") }
            Text("本地运行，无联网权限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable private fun Preview(model: AssistantModel) {
    val state = model.state
    state.preview?.let { patch -> AlertDialog(onDismissRequest = model::dismiss, title = { Text(if (state.previewMode == AssistantMode.QUICK) "确认快捷解锁" else "确认所选解锁") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("将关闭游戏、核对原件、创建备份并写回。现有本地读取开关会设为 0，不操作云存档。") }
            items(patch.changes) { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(model::apply, enabled = !state.busy) { Text("备份并应用") } }, dismissButton = { TextButton(model::dismiss) { Text("取消") } }) }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f), contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun Status(state: AssistantState) {
    Panel { if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun Section(title: String, description: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun CheckLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
