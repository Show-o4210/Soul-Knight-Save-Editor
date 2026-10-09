package com.example.soul_knight_save_editor.unlock

import android.app.Activity
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var headerCompact by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cheerTrigger = remember(model) { CheerTrigger(model.hasDiscoveredCheer()) }
    var cheerFirst by remember { mutableStateOf<Boolean?>(null) }
    var cheerToast by remember { mutableStateOf<Toast?>(null) }
    DisposableEffect(Unit) { onDispose { cheerToast?.cancel() } }
    val titleClick = {
        headerCompact = false
        if (!state.busy) {
            val event = cheerTrigger.tap(SystemClock.elapsedRealtime())
            if (event.triggered) {
                cheerToast?.cancel()
                if (event.firstDiscovery) model.markCheerDiscovered()
                cheerFirst = event.firstDiscovery
            } else event.hint?.let { hint ->
                cheerToast?.cancel()
                cheerToast = Toast.makeText(context, hint, Toast.LENGTH_SHORT).also { it.show() }
            }
        }
    }
    LaunchedEffect(state.mode, state.page, state.workspaceTab) { headerCompact = false }
    val headerScroll = remember(state.mode, state.page, state.workspaceTab) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    if (distance * consumed.y < 0) distance = 0f
                    distance += consumed.y
                    if (distance < -24f) headerCompact = true
                    if (distance > 24f) headerCompact = false
                }
                return Offset.Zero
            }
        }
    }
    SideEffect { (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = state.mode == AssistantMode.QUICK } }
    BackHandler(state.mode != null && state.preview == null && state.applyOutcome == null) {
        if (state.busy) model.notice("操作进行中，请等待完成")
        else model.back()
    }
    AnimatedContent(targetState = state.mode to state.page, label = "mode-transition", transitionSpec = {
        if (initialState.first != targetState.first)
            (fadeIn(tween(240)) + scaleIn(tween(340), initialScale = .95f) + slideInVertically(tween(340)) { it / 14 }) togetherWith
                (fadeOut(tween(150)) + scaleOut(tween(220), targetScale = 1.03f) + slideOutVertically(tween(220)) { -it / 18 })
        else (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 16 }) togetherWith
            (fadeOut(tween(120)) + slideOutHorizontally(tween(180)) { -it / 16 })
    }) { (mode, page) ->
        val palette = when (mode) { null -> guideColors; AssistantMode.QUICK -> quickColors; AssistantMode.EXPERT -> expertColors }
        MaterialTheme(colorScheme = palette) {
            Box(Modifier.fillMaxSize()) {
                Image(painterResource(if (mode == AssistantMode.QUICK) R.drawable.background_quick else R.drawable.background_expert), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                if (mode == null) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                    listOf(Color(0xFF837397).copy(alpha = .86f), Color(0xFF5D506E).copy(alpha = .88f)))))
                else Box(Modifier.matchParentSize().background(palette.background.copy(alpha = if (mode == AssistantMode.EXPERT) .60f else .68f)))
                Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp).nestedScroll(headerScroll)) {
                    AnimatedHeader(mode, page, state.busy, headerCompact, titleClick, model::guide,
                        { model.page(AssistantPage.SETTINGS) }, model::back)
                    if (mode == null) Guide(model, Modifier.weight(1f))
                    else when (page) {
                        AssistantPage.WORKSPACE -> WorkspaceContent(model, mode, Modifier.weight(1f))
                        AssistantPage.BACKUPS -> Backups(model, chooseFolder, openFolder, Modifier.weight(1f))
                        AssistantPage.SETTINGS -> Settings(model, Modifier.weight(1f))
                    }
                    if (mode != null && page == AssistantPage.WORKSPACE) MainTabs(state, model)
                }
            }
        }
    }
    MaterialTheme(colorScheme = when (state.mode) { null -> guideColors; AssistantMode.QUICK -> quickColors; AssistantMode.EXPERT -> expertColors }) {
        Preview(model)
        state.applyOutcome?.let { ApplyOutcomeDialog(it, model::dismissApplyOutcome) }
        cheerFirst?.let { first -> ShowcheerEgg(first) { cheerFirst = null } }
        if (state.consentPromptVisible) AlertDialog(onDismissRequest = model::declinePersonalUse,
            title = { Text("个人本地存档使用确认") },
            text = { Text("本工具只应处理你有权访问的个人手机或虚拟机上的本地存档。修改前请查看预览并保留备份；修改结果及使用后果由你自行确认。确认后才可使用写入功能。") },
            confirmButton = { Button(model::acceptPersonalUse) { Text("我已了解并同意") } },
            dismissButton = { TextButton(model::declinePersonalUse) { Text("暂不使用写入") } })
    }
}

/** Add future features here: stable tab ID, label and renderer. Navigation has no fixed tab count. */
private data class WorkspaceSection(val tab: WorkspaceTab, val render: @Composable (AssistantModel, AssistantMode, Modifier) -> Unit)
private val workspaceSections = listOf(
    WorkspaceSection(WorkspaceTabs.saves) { model, _, modifier -> SavesPage(model, modifier) },
    WorkspaceSection(WorkspaceTabs.characters) { model, mode, modifier -> CharactersPage(model, mode, modifier) },
    WorkspaceSection(WorkspaceTabs.items) { model, mode, modifier -> ItemsPage(model, mode, modifier) },
    WorkspaceSection(WorkspaceTabs.weapons) { model, mode, modifier -> WeaponsPage(model, mode, modifier) }
)

@Composable private fun WorkspaceContent(model: AssistantModel, mode: AssistantMode, modifier: Modifier) {
    val holder = rememberSaveableStateHolder()
    val section = workspaceSections.firstOrNull { it.tab.id == model.state.workspaceTab } ?: workspaceSections.first()
    holder.SaveableStateProvider(section.tab.id) { section.render(model, mode, modifier) }
}

@Composable private fun MainTabs(state: AssistantState, model: AssistantModel) {
    WorkspaceTabBar(state, workspaceSections.map { it.tab }, model::tab)
}

@Composable private fun Guide(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    Column(modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard("快速模式", "角色、物品与武器快捷修改", "选择角色、物品或武器获取次数；预览后再应用。", false,
            Modifier.weight(1f).fillMaxWidth()) { model.chooseMode(AssistantMode.QUICK) }
        ModeCard("专家模式", "角色、物品与武器逐项选择", "选择条目与参数，预览后统一写回。", true,
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

@Composable private fun CharactersPage(model: AssistantModel, renderMode: AssistantMode, modifier: Modifier) {
    val state = model.state
    val catalog = state.catalog
    val quick = renderMode == AssistantMode.QUICK
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel(title = "角色", help = HelpTopics.roles) {
                catalog?.let { Text(accountLabel(it.account), style = MaterialTheme.typography.labelSmall) }
                if (catalog == null) {
                    Text("请先读取存档。")
                    OutlinedButton({ model.tab(WorkspaceTabs.saves) }, enabled = !state.busy) { Text("前往存档") }
                }
            } }
            if (catalog != null && quick && catalog.heroes.isNotEmpty()) item { Panel(title = "角色与技能", help = HelpTopics.roles) {
                CheckLine("全部角色解锁", state.choices.quickRoles, !state.busy) { model.quick(roles = it) }
                CheckLine("全部皮肤解锁", state.choices.quickSkins, !state.busy) { model.quick(skins = it) }
                CheckLine("角色等级至少 7 级", state.choices.quickLevels, !state.busy && !catalog.xmlOnly && state.progression.isNotEmpty() && state.progression.all { it.level != null }) { model.quick(levels = it) }
                CheckLine("解锁已有技能条目", state.choices.quickSkills, !state.busy && !catalog.xmlOnly && state.progression.isNotEmpty() && state.progression.all { it.skillError == null }) { model.quick(skills = it) }
                if (catalog.xmlOnly) Text("等级与技能暂不可用", style = MaterialTheme.typography.labelSmall)
                if (!state.personalUseAccepted) TextButton(model::requestConsent) { Text("先确认个人使用约定") }
            } }
            if (catalog != null && quick) item { Panel(title = "宠物", help = HelpTopics.pets) {
                Text(state.petMessage, style = MaterialTheme.typography.bodySmall)
                CheckLine("解锁已有宠物（${state.petStates.size} 项）", state.choices.quickPets,
                    !state.busy && state.petStates.isNotEmpty()) { model.quick(pets = it) }
            } }
            item { Status(state) }
            if (state.pending.isNotEmpty()) item { Panel {
                Text("有未完成的写回，请先恢复", fontWeight = FontWeight.Bold)
                Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy) { Text("前往备份与恢复") }
            } }
            if (catalog != null && !quick) {
                item { Panel(title = "选择角色", help = HelpTopics.roles) {
                    OutlinedTextField(query, { query = it }, label = { Text("角色名称或编号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CheckLine("只看已选角色条目", selectedOnly, true) { selectedOnly = it }
                } }
                val selected = state.choices.expert
                val heroes = catalog.heroes.filter { (it.label().contains(query, true) || it.name.contains(query, true)) &&
                    (!selectedOnly || it.index in selected.heroes || it.index in selected.levelHeroes || selected.skins.any { skin -> skin.hero == it.index } || selected.skillIds.any { skill -> skill.hero == it.index }) }
                if (heroes.isEmpty()) item { Panel { Text("没有匹配条目，试试角色内部名或编号。") } }
                item { Panel(title = "批量选择", help = HelpTopics.roles) {
                    val selected = state.choices.expert
                    Text("角色 ${selected.heroes.size} · 皮肤 ${selected.skins.size} · 等级 ${selected.levelHeroes.size} · 技能 ${selected.skillIds.size} · 宠物 ${selected.petIds.size} 项待修改")
                    TextButton({ model.select(selected.copy(heroes = selected.heroes + heroes.filter { it.unlocked == false }.map { it.index },
                        skins = selected.skins + heroes.flatMap { hero -> hero.skins.filterValues { it != 1 }.keys.map { SkinId(hero.index, it) } })) }, enabled = !state.busy) { Text("选择搜索结果的未解锁角色与皮肤") }
                    TextButton({ model.select(UnlockSelection()) }, enabled = !state.busy) { Text("清空角色页全部选择") }
                    TextButton({ model.select(selected.copy(levelHeroes = selected.levelHeroes + state.progression.filter { it.level != null && heroes.any { hero -> hero.index == it.hero } }.map { it.hero })) }, enabled = !state.busy) { Text("选择结果的可用等级") }
                    TextButton({ model.select(selected.copy(skillIds = selected.skillIds + state.progression.filter { heroes.any { hero -> hero.index == it.hero } }.flatMap { progress -> progress.skills.filterValues { !it }.keys.map { SkillId(progress.hero, it) } })) }, enabled = !state.busy) { Text("选择结果的未解锁技能") }
                } }
                items(heroes, key = { "hero-${it.index}" }) { hero -> HeroCard(hero, state, model) }
                item { Panel(title = "宠物 · ${state.choices.expert.petIds.size} 项待解锁", help = HelpTopics.pets) {
                    Text(state.petMessage)
                    TextButton({ model.select(state.choices.expert.copy(petIds = state.choices.expert.petIds + state.petStates.filter { !it.unlocked && (it.definition.label.contains(query, true) || it.definition.id.contains(query, true)) }.map { it.definition.id })) }, enabled = !state.busy) { Text("选择搜索结果的未解锁宠物") }
                } }
                items(state.petStates.filter { (it.definition.label.contains(query, true) || it.definition.id.contains(query, true)) && (!selectedOnly || it.definition.id in selected.petIds) }, key = { "pet-${it.definition.id}" }) { pet -> Panel(title = pet.definition.label, help = HelpTopic(pet.definition.label, "${HelpTopics.pets.text}\n\n宠物 ID：${pet.definition.id}")) {
                    CheckLine(if (pet.unlocked) "已解锁" else "解锁此宠物", pet.unlocked || pet.definition.id in state.choices.expert.petIds,
                        !state.busy && !pet.unlocked) { checked ->
                        val selection = state.choices.expert
                        model.select(selection.copy(petIds = if (checked) selection.petIds + pet.definition.id else selection.petIds - pet.definition.id))
                    }
                } }

            }
        }
        PendingEdits(state, renderMode, model::preview)
    }
}

@Composable internal fun PendingEdits(state: AssistantState, renderMode: AssistantMode, onPreview: () -> Unit) {
    val catalog = state.catalog ?: return
    val quick = renderMode == AssistantMode.QUICK
    val characters = EditEngine.hasCharacters(state.choices.selection(renderMode, catalog))
    val items = state.itemsFor(renderMode).hasChanges
    val weapons = if (quick) state.choices.quickWeapons else state.expertWeapons.hasChanges
    Text("跨页待修改：角色${if (characters) "已选" else "未选"} · 物品${if (items) "已选" else "未选"} · 武器${if (weapons) "已选" else "未选"}",
        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
    Button(onPreview, enabled = !state.busy && state.pending.isEmpty() && state.personalUseAccepted && state.mode == renderMode &&
        (characters || items || weapons) && state.itemsFor(renderMode).valid && (quick || state.expertWeapons.valid),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 52.dp).testTag("preview-all-edits")) {
        Text("预览全部待修改内容")
    }
}

@Composable private fun HeroCard(hero: Hero, state: AssistantState, model: AssistantModel) {
    var expanded by rememberSaveable(hero.index) { mutableStateOf(false) }
    val selection = state.choices.expert
    Panel(title = hero.label(), help = HelpTopics.roles) {
        Text("${hero.skins.count { it.value == 1 }}/${hero.skins.size} 皮肤已解锁", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hero.unlocked != null) CheckLine(if (hero.unlocked) "角色已解锁" else "解锁角色", hero.unlocked || hero.index in selection.heroes, !state.busy && !hero.unlocked) {
            model.select(selection.copy(heroes = if (it) selection.heroes + hero.index else selection.heroes - hero.index))
        }
        val progress = state.progression.firstOrNull { it.hero == hero.index }
        CheckLine("等级至少 7 · 当前 ${progress?.level ?: "不可用"}", hero.index in selection.levelHeroes,
            !state.busy && progress?.level != null) {
            model.select(selection.copy(levelHeroes = if (it) selection.levelHeroes + hero.index else selection.levelHeroes - hero.index))
        }
        progress?.levelError?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        progress?.skillError?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        progress?.skills?.forEach { (id, unlocked) ->
            val skill = SkillId(hero.index, id)
            CheckLine("技能 #$id${if (unlocked) " · 已解锁" else ""}", unlocked || skill in selection.skillIds, !state.busy && !unlocked) {
                model.select(selection.copy(skillIds = if (it) selection.skillIds + skill else selection.skillIds - skill))
            }
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
            Text("当前版本：${state.packageName}", style = MaterialTheme.typography.labelSmall)
            Text("当前存档备份包含本地 .data 分片与识别到的 XML，不含 .data.new 或其他账号配置。备份含个人存档，请勿公开分享。", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel(title = "备份文件夹", help = HelpTopics.backups) {
            Text(state.folder?.let { BackupDestination.label(Uri.parse(it)) } ?: "尚未选择。建议在“文档”中建立专用文件夹。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(chooseFolder, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.folder == null) "选择文件夹" else "更换目录") }
                OutlinedButton(openFolder, enabled = !state.busy && state.folder != null, modifier = Modifier.weight(1f)) { Text("打开文件夹") }
            }
            CheckLine("导出成功后自动打开文件夹", state.autoOpenFolder, !state.busy, model::autoOpen)
            Button(model::backupNow, enabled = !state.busy && state.folder != null && state.pending.isEmpty(), modifier = Modifier.fillMaxWidth()) { Text("备份当前本地存档") }
        } }
        item { Status(state) }
        items(state.pending, key = { "pending-$it" }) { id -> Panel {
            Text("未完成的写回", fontWeight = FontWeight.Bold)
            Text(state.pendingPackages[id] ?: "待核对目标", style = MaterialTheme.typography.labelSmall)
            Text(timestamp(id.substringBefore('-').toLongOrNull() ?: 0))
            Button({ model.restore(id, true) }, enabled = !state.busy) { Text("恢复未完成事务") }
        } }
        val archives = state.archives.filter { it.packageName == state.packageName }
        if (archives.isNotEmpty()) item { Section("当前版本的备份包", "应用内还保留一份副本；外部目录中的文件不受卸载助手影响。") }
        items(archives, key = { it.file.name }) { info -> Panel {
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

    }
    restoreId?.let { id -> AlertDialog(onDismissRequest = { restoreId = null }, title = { Text("恢复修改前原件？") },
        text = { Text("会关闭游戏并核对文件。若游戏已产生后续变化，将拒绝覆盖，保留备份。") },
        confirmButton = { Button({ restoreId = null; model.restore(id, false) }) { Text("确认恢复") } }, dismissButton = { TextButton({ restoreId = null }) { Text("取消") } }) }
}

@Composable private fun Settings(model: AssistantModel, modifier: Modifier) {
    val state = model.state
    val context = LocalContext.current
    var showDiagnostic by remember { mutableStateOf(false) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Panel {
            Text("公共设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("当前游戏：${state.packageName}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ model.tab(WorkspaceTabs.saves) }, enabled = !state.busy) { Text("选择游戏与扫描存档") }
            Button({ model.page(AssistantPage.BACKUPS) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("settings-backups")) { Text("备份与恢复${if (state.pending.isNotEmpty()) " · 有待恢复事务" else ""}") }
        } }
        item { Panel("存档访问方式") {
            AccessBackend.entries.forEach { backend ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.accessBackend == backend,
                        onClick = { model.accessBackend(backend) }, enabled = !state.busy && state.pending.isEmpty(),
                        modifier = Modifier.testTag("access-${backend.id}"))
                    Text(if (backend == AccessBackend.NATIVE_ROOT) "原生 Root（默认）" else "Shizuku Root（备选）")
                }
            }
            Text("Shizuku Root 需要另外安装 Shizuku，并通过 Root 启动。", style = MaterialTheme.typography.bodySmall)
            Text(state.accessStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("access-status"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ model.checkAccess() }, enabled = !state.busy) { Text("检查／重试") }
                if (state.accessBackend == AccessBackend.SHIZUKU_ROOT) {
                    OutlinedButton({ model.checkAccess(requestPermission = true) }, enabled = !state.busy) { Text("授权 Shizuku") }
                }
            }
            if (state.pending.isNotEmpty()) Text("有未完成事务，请先使用原访问方式恢复。", style = MaterialTheme.typography.bodySmall)
            if (state.accessDiagnostic.isNotEmpty()) {
                TextButton({ showDiagnostic = !showDiagnostic }) { Text(if (showDiagnostic) "收起本地诊断" else "查看本地诊断") }
                if (showDiagnostic) {
                    Text(state.accessDiagnostic, style = MaterialTheme.typography.bodySmall)
                    TextButton({
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("存档访问诊断", state.accessDiagnostic))
                        model.notice("本地诊断已复制。")
                    }) { Text("复制诊断") }
                }
            }
        } }
        item { Panel {
            Text("启动与模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            CheckLine("记住上次使用的模式", state.rememberMode, !state.busy, model::rememberMode)
            OutlinedButton(model::guide, enabled = !state.busy) { Text("重新查看入口引导") }
        } }
        item { Panel {
            Text("共同的边界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("两种模式使用相同的备份和写回校验。快捷模式提供角色、宠物、等级、技能及已确认物品操作；详细模式仍只处理角色与皮肤。不操作 .data.new 及游玩记录。")
            if (!state.personalUseAccepted) OutlinedButton(model::requestConsent) { Text("确认个人使用约定") }
            Text("本地运行，无联网权限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable private fun Preview(model: AssistantModel) {
    val state = model.state
    var expanded by remember(state.preview) { mutableStateOf(false) }
    state.preview?.let { patch -> AlertDialog(onDismissRequest = model::dismiss, title = { FeatureTitle("确认本次修改", HelpTopic("修改说明", "目标版本：${state.snapshot?.packageName}\n\n应用前会关闭游戏、核对原件、创建备份并写入。账号已有的本地读取开关会设为 0；缺失开关不新增。切换模式不会混入另一套草稿。")) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("${state.catalog?.account?.let(::accountLabel)} · ${if (state.previewMode == AssistantMode.QUICK) "快速模式" else "专家模式"}\n将修改 ${patch.outputs.size} 份存档，修改前会自动备份。") }
            items(patch.sections.entries.toList()) { (name, entries) -> Text("$name：${entries.size} 项变化", fontWeight = FontWeight.Bold) }
            item { TextButton({ expanded = !expanded }) { Text(if (expanded) "收起逐项变化" else "展开 ${patch.changes.size} 项变化") } }
            if (expanded) items(patch.changes) { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(model::apply, enabled = !state.busy) { Text("备份并应用") } }, dismissButton = { TextButton(model::dismiss) { Text("取消") } }) }
}

@Composable internal fun ApplyOutcomeDialog(outcome: ApplyOutcome, onDismiss: () -> Unit) {
    var expanded by remember(outcome) { mutableStateOf(false) }
    val accent = if (outcome.succeeded) Color(0xFF218548) else Color(0xFFB3261E)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("apply-outcome-dialog"),
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(88.dp).background(accent.copy(alpha = .12f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(if (outcome.succeeded) "✓" else "!", color = accent, fontSize = 56.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("apply-outcome-symbol"))
                }
                Text(if (outcome.succeeded) "修改成功" else "修改未完成", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(outcome.packageName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (outcome.succeeded) "${outcome.fileCount} 个文件已写入并校验。请进入游戏确认显示结果。"
                    else "本次事务没有完成，请查看失败原因和备份状态。")
                TextButton({ expanded = !expanded }, modifier = Modifier.testTag("apply-outcome-toggle")) {
                    Text(if (expanded) "收起详细信息" else "展开详细信息")
                }
                if (expanded) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("apply-outcome-details"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text("成功项（${outcome.successful.size}）", fontWeight = FontWeight.SemiBold) }
                    items(outcome.successful) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    item { Text("失败项（${outcome.failed.size}）", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)) }
                    if (outcome.failed.isEmpty()) item { Text("无", style = MaterialTheme.typography.bodySmall) }
                    else items(outcome.failed) { Text(it, style = MaterialTheme.typography.bodySmall, color = accent) }
                }
            }
        },
        confirmButton = { Button(onDismiss) { Text("完成") } }
    )
}

@Composable internal fun Panel(title: String? = null, help: HelpTopic? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f), contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) FeatureTitle(title, help)
            content()
        }
    }
}
@Composable internal fun Status(state: AssistantState) {
    Panel { if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.message, style = MaterialTheme.typography.bodySmall) }
}
@Composable private fun Section(title: String, description: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
}
@Composable internal fun CheckLine(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
