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

private val relockHelp = HelpTopic("重新锁定", "优先恢复本工具备份中解锁前的原值；无可用备份时，只使用已核对且匹配当前版本的锁值。基础角色、基础皮肤和基础技能保留。锁值未知或镜像不一致的条目会跳过。\n\nXML 与 .data 镜像一同处理；如果当前使用的角色、皮肤或技能被锁定，会在预览中列出回退到基础内容的变化。请查看预览后再应用。")

@Composable internal fun CharacterActionSelector(relock: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(!relock, { onChange(false) }, enabled = enabled, label = { Text("解锁") },
                modifier = Modifier.testTag("characters-unlock"))
            FilterChip(relock, { onChange(true) }, enabled = enabled, label = { Text("重新锁定") },
                modifier = Modifier.testTag("characters-relock"))
        }
        Text("切换操作会清空当前模式的角色页选择。", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable internal fun RelockCharacterContent(state: AssistantState, mode: AssistantMode, modifier: Modifier = Modifier,
    onRelock: (Boolean) -> Unit, onQuick: (Boolean, Boolean, Boolean) -> Unit,
    onSelect: (UnlockSelection) -> Unit, onPreview: () -> Unit, onSaves: () -> Unit,
    onBackups: () -> Unit, onConsent: () -> Unit) {
    val catalog = state.catalog
    val inventory = state.relockInventory
    val quick = mode == AssistantMode.QUICK
    val selection = state.choices.expert
    var query by rememberSaveable(state.packageName, catalog?.account) { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var showSkipped by rememberSaveable(state.packageName, catalog?.account) { mutableStateOf(false) }
    val heroes = catalog?.heroes.orEmpty().filter { hero ->
        (hero.label().contains(query, true) || hero.name.contains(query, true)) &&
            (!selectedOnly || hero.index in selection.heroes || selection.skins.any { it.hero == hero.index } ||
                selection.skillIds.any { it.hero == hero.index })
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).testTag("characters-relock-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 14.dp)) {
            item { Panel(title = "角色", help = relockHelp) {
                CharacterActionSelector(true, !state.busy, onRelock)
                Text("优先恢复解锁前原值；基础内容保留，锁值未知的条目跳过。")
                Text("同步 XML 与 .data；正在使用的内容被锁定时回退基础内容，并列入预览。", style = MaterialTheme.typography.bodySmall)
                if (catalog == null) {
                    Text("请先读取存档。")
                    OutlinedButton(onSaves, enabled = !state.busy) { Text("前往存档") }
                }
            } }
            item { Panel(title = "重新锁定范围", help = relockHelp) {
                Text(state.relockMessage, style = MaterialTheme.typography.bodySmall)
                if (inventory != null) {
                    Text("可重新锁定：角色 ${inventory.heroes.size} · 皮肤 ${inventory.skins.size} · 技能 ${inventory.skills.size}")
                    if (inventory.skipped.isNotEmpty()) TextButton({ showSkipped = !showSkipped }) {
                        Text(if (showSkipped) "收起跳过说明（${inventory.skipped.size} 项）" else "查看跳过说明（${inventory.skipped.size} 项）")
                    }
                }
            } }
            if (showSkipped) items(inventory?.skipped.orEmpty()) { reason -> Panel {
                Text(reason, style = MaterialTheme.typography.bodySmall)
            } }
            if (catalog != null && quick) item { Panel(title = "批量重新锁定", help = relockHelp) {
                CheckLine("重新锁定已确认的全部角色", state.choices.quickRoles,
                    !state.busy && inventory?.heroes?.isNotEmpty() == true) {
                    onQuick(it, state.choices.quickSkins, state.choices.quickSkills)
                }
                CheckLine("重新锁定已确认的全部皮肤", state.choices.quickSkins,
                    !state.busy && inventory?.skins?.isNotEmpty() == true) {
                    onQuick(state.choices.quickRoles, it, state.choices.quickSkills)
                }
                CheckLine("重新锁定已确认的全部技能", state.choices.quickSkills,
                    !state.busy && inventory?.skills?.isNotEmpty() == true) {
                    onQuick(state.choices.quickRoles, state.choices.quickSkins, it)
                }
            } }
            item { Status(state) }
            if (state.pending.isNotEmpty()) item { Panel {
                Text("有未完成的写回，请先恢复", fontWeight = FontWeight.Bold)
                Button(onBackups, enabled = !state.busy) { Text("前往备份与恢复") }
            } }
            if (!state.personalUseAccepted) item { TextButton(onConsent) { Text("先确认个人使用约定") } }
            if (catalog != null && !quick) {
                item { Panel(title = "选择重新锁定的内容", help = relockHelp) {
                    OutlinedTextField(query, { query = it }, label = { Text("角色名称或编号") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    CheckLine("只看已选角色条目", selectedOnly, true) { selectedOnly = it }
                    Text("角色 ${selection.heroes.size} · 皮肤 ${selection.skins.size} · 技能 ${selection.skillIds.size} 项待重新锁定")
                    TextButton({
                        val ids = heroes.map { it.index }.toSet()
                        onSelect(selection.copy(heroes = selection.heroes + inventory?.heroes.orEmpty().filter { it in ids },
                            skins = selection.skins + inventory?.skins.orEmpty().filter { it.hero in ids },
                            skillIds = selection.skillIds + inventory?.skills.orEmpty().filter { it.hero in ids }))
                    }, enabled = !state.busy && inventory != null) { Text("选择搜索结果的全部可重新锁定内容") }
                    TextButton({ onSelect(UnlockSelection(relock = true)) }, enabled = !state.busy) { Text("清空重新锁定选择") }
                } }
                if (heroes.isEmpty()) item { Panel { Text("没有匹配条目，试试角色内部名或编号。") } }
                items(heroes, key = { "relock-hero-${it.index}" }) { hero ->
                    RelockHeroCard(hero, state, onSelect)
                }
            }
        }
        PendingEdits(state, mode, onPreview)
    }
}

@Composable private fun RelockHeroCard(hero: Hero, state: AssistantState, onSelect: (UnlockSelection) -> Unit) {
    val inventory = state.relockInventory
    val selection = state.choices.expert
    var expanded by rememberSaveable(hero.index) { mutableStateOf(false) }
    Panel(title = hero.label(), help = relockHelp) {
        val canRelockHero = hero.index in inventory?.heroes.orEmpty()
        CheckLine(when {
            hero.index == 0 -> "基础角色保留"
            hero.unlocked != true -> "角色未解锁或状态不可用"
            !canRelockHero -> "角色暂不可重新锁定，请查看跳过说明"
            else -> "重新锁定角色"
        }, hero.index in selection.heroes, !state.busy && canRelockHero) {
            onSelect(selection.copy(heroes = if (it) selection.heroes + hero.index else selection.heroes - hero.index))
        }
        val skills = (state.progression.firstOrNull { it.hero == hero.index }?.skills.orEmpty().keys +
            inventory?.skills.orEmpty().filter { it.hero == hero.index }.map { it.skill }).toSortedSet()
        skills.forEach { id ->
            val skill = SkillId(hero.index, id)
            val available = skill in inventory?.skills.orEmpty()
            CheckLine(when {
                id == 0 -> "技能 #0 · 基础技能保留"
                available -> "重新锁定技能 #$id"
                else -> "技能 #$id · 未解锁或暂不可重新锁定"
            }, skill in selection.skillIds, !state.busy && available) {
                onSelect(selection.copy(skillIds = if (it) selection.skillIds + skill else selection.skillIds - skill))
            }
        }
        TextButton({ expanded = !expanded }) { Text(if (expanded) "收起皮肤" else "展开皮肤（${hero.skins.size} 项）") }
        if (expanded) hero.skins.toSortedMap().forEach { (id, value) ->
            val skin = SkinId(hero.index, id)
            val available = skin in inventory?.skins.orEmpty()
            CheckLine(when {
                id == 0 -> "皮肤 #0 · 基础皮肤保留"
                value != 1 -> "皮肤 #$id · 未解锁"
                available -> "重新锁定皮肤 #$id"
                else -> "皮肤 #$id · 锁值未知或镜像不可用，跳过"
            }, skin in selection.skins, !state.busy && available) {
                onSelect(selection.copy(skins = if (it) selection.skins + skin else selection.skins - skin))
            }
        }
    }
}
