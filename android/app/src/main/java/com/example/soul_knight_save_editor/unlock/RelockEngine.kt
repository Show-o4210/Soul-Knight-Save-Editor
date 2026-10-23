package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.w3c.dom.Element

data class RelockScope(val packageName: String, val user: Int, val versionName: String? = null, val versionCode: Int? = null)

/** Verified originals, newest first. Callers must retain the package/user/account identity of each backup. */
data class RelockOriginal(val packageName: String, val user: Int, val account: String, val prefs: ByteArray)

data class RelockInventory(val heroes: Set<Int> = emptySet(), val skins: Set<SkinId> = emptySet(),
    val skills: Set<SkillId> = emptySet(), val skipped: List<String> = emptyList())

/** Restore observed locked states only; this never invents a universal skin price or a missing key. */
object RelockEngine {
    private data class Lock(val raw: String, val source: String)
    private data class Prepared(val catalog: Catalog, val root: JsonObject, val nodes: Map<String, Element>,
        val heroLocks: Map<Int, Lock>, val skinLocks: Map<SkinId, Lock>, val skillLocks: Map<SkillId, Lock>,
        val skipped: List<String>) {
        fun inventory() = RelockInventory(heroLocks.keys, skinLocks.keys, skillLocks.keys, skipped)
    }

    private fun key(account: String, suffix: String) = if (account.isEmpty()) suffix else "${account}_$suffix"
    private fun raw(node: Element) = if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
    private fun roleValue(node: Element): Boolean? = if (node.tagName !in setOf("int", "string", "boolean")) null else when (raw(node).lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
    private fun integer(node: Element?) = node?.takeIf { it.tagName == "int" }?.let { raw(it).toIntOrNull() }
    private fun bool(value: JsonElement?) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun number(value: JsonElement?) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private fun validSkills(value: JsonElement?): JsonArray? {
        val entries = value as? JsonArray ?: return null
        val ids = mutableSetOf<Int>()
        return entries.takeIf { list -> list.all { entry ->
            val item = entry as? JsonObject
            val id = number(item?.get("Key"))
            id != null && id >= 0 && ids.add(id) && bool(item?.get("Value")) != null
        } }
    }

    private fun selected(root: JsonObject, field: String, hero: String): Int {
        val element = root[field] ?: return 0
        val entries = element as? JsonObject ?: error("$field 当前选择格式未知")
        val value = entries[hero] ?: return 0
        return number(value)?.takeIf { it >= 0 } ?: error("$hero 的 $field 当前选择格式未知")
    }

    private fun selectedSkill(root: JsonObject, nodes: Map<String, Element>, account: String, hero: String): Int {
        val gameValue = selected(root, "heroSkill", hero)
        val node = nodes[key(account, "c_${hero}_skill")]
        val prefValue = if (node == null) 0 else integer(node)?.takeIf { it >= 0 } ?: error("当前技能 XML 格式未知")
        require(gameValue == prefValue) { "当前技能 XML 与 game.data 镜像不一致" }
        return gameValue
    }

    private fun superHeroChoices(root: JsonObject, nodes: Map<String, Element>, account: String): Pair<Int, Int> {
        val element = root["superHeroInHeroRoom"]
        val gameValue = if (element == null) 0 else number(element)?.takeIf { it >= 0 } ?: error("大厅角色选择格式未知")
        val node = nodes[key(account, "super_hero_in_hero_room")]
        val prefValue = if (node == null) 0 else integer(node)?.takeIf { it >= 0 } ?: error("大厅角色 XML 格式未知")
        return gameValue to prefValue
    }

    private fun baseSkinAvailable(root: JsonObject, hero: Hero): Boolean {
        val entries = root.getValue("skinLock").jsonObject[hero.name] as? JsonArray ?: return false
        val base = entries.singleOrNull { number((it as? JsonObject)?.get("Key")) == 0 } as? JsonObject
        return hero.skins[0] == 1 && number(base?.get("Value")) == 1
    }

    private fun baseSkillAvailable(root: JsonObject, nodes: Map<String, Element>, account: String, hero: Hero): Boolean {
        val entries = (root["heroSkillUnlock"] as? JsonObject)?.get(hero.name) as? JsonArray ?: return false
        val base = entries.singleOrNull { number((it as? JsonObject)?.get("Key")) == 0 } as? JsonObject
        return bool(base?.get("Value")) == true && integer(nodes[key(account, "c_${hero.name}_skill_0_unlock")]) == 1
    }

    /** Check only choices affected by this target, so an unrelated malformed choice does not block it. */
    private fun choiceProblem(root: JsonObject, nodes: Map<String, Element>, account: String, catalog: Catalog,
        hero: Hero, role: Boolean = false, skin: Int? = null, skill: Int? = null): String? = runCatching {
        if (role || skin != null) {
            val current = selected(root, "currentSkinDatas", hero.name)
            if (current != 0 && (role || current == skin)) require(baseSkinAvailable(root, hero)) { "基础皮肤缺失、未解锁或镜像不一致，无法回退" }
        }
        if (role || skill != null) {
            val current = selectedSkill(root, nodes, account, hero.name)
            if (current != 0 && (role || current == skill)) require(baseSkillAvailable(root, nodes, account, hero)) { "基础技能缺失、未解锁或镜像不一致，无法回退" }
        }
        if (role) {
            val (gameChoice, prefChoice) = superHeroChoices(root, nodes, account)
            if (hero.index == gameChoice || hero.index == prefChoice) {
                require(gameChoice == prefChoice) { "大厅角色 XML 与 game.data 镜像不一致" }
                val base = catalog.heroes.singleOrNull { it.index == 0 }
                require(base?.unlocked == true && bool(root.getValue("heroUnlock").jsonObject[base.name]) == true) { "基础角色未解锁或镜像不一致，无法回退" }
            }
        }
    }.exceptionOrNull()?.message

    fun inspect(gameBytes: ByteArray?, prefsBytes: ByteArray, account: String, scope: RelockScope,
        originals: List<RelockOriginal> = emptyList()): RelockInventory {
        if (gameBytes == null) return RelockInventory(skipped = listOf("重新锁定需要 game.data 与 XML 同步核对，当前为 XML 单文件模式，已跳过"))
        return prepare(gameBytes, prefsBytes, account, scope, originals).inventory()
    }

    private fun prepare(gameBytes: ByteArray, prefsBytes: ByteArray, account: String, scope: RelockScope,
        originals: List<RelockOriginal>): Prepared {
        val catalog = UnlockEngine.catalog(gameBytes, prefsBytes, account)
        val nodes = UnlockEngine.prefs(prefsBytes)
        val root = UnlockEngine.game(gameBytes)
        val history = originals.filter { it.packageName == scope.packageName && it.user == scope.user && it.account == account }
            .mapNotNull { runCatching { UnlockEngine.prefs(it.prefs) }.getOrNull() }
        val useReference = scope.packageName == "com.knight.union.bili" && scope.versionName == "8.7.0" &&
            (scope.versionCode == null || scope.versionCode == 80700) &&
            catalog.heroes.all { it.index in referenceNames.indices && referenceNames[it.index] == it.name }
        val skipped = mutableListOf<String>()
        fun lock(node: Element, suffix: String, valid: (Element) -> Boolean, fallback: String?): Lock? {
            history.forEach { old -> old[key(account, suffix)]?.let {
                if (it.tagName == node.tagName && valid(it)) return Lock(raw(it), "解锁前原值")
            } }
            return fallback?.takeIf { useReference }?.let { Lock(it, "B站 8.7 实测参考") }
        }
        val heroLocks = linkedMapOf<Int, Lock>()
        val skinLocks = linkedMapOf<SkinId, Lock>()
        val skillLocks = linkedMapOf<SkillId, Lock>()
        val heroMirror = root.getValue("heroUnlock").jsonObject
        val skinMirror = root.getValue("skinLock").jsonObject
        val skillMirror = root["heroSkillUnlock"] as? JsonObject
        catalog.heroes.forEach { hero ->
            if (hero.index != 0 && hero.unlocked == true) {
                val node = nodes.getValue(key(account, "c${hero.index}_unlock"))
                val problem = choiceProblem(root, nodes, account, catalog, hero, role = true)
                if (bool(heroMirror[hero.name]) != true) skipped += "${hero.name}：角色镜像不一致，已跳过"
                else if (problem != null) skipped += "${hero.name}：$problem，已跳过" else {
                    val fallback = if (hero.index in 1..41 && node.tagName == "string") "false" else null
                    val state = lock(node, "c${hero.index}_unlock", { roleValue(it) == false }, fallback)
                    if (state == null) skipped += "${hero.name}：没有已知的角色原锁值，已跳过" else heroLocks[hero.index] = state
                }
            }
            hero.skins.filter { it.key != 0 && it.value == 1 }.forEach { (id, _) ->
                val target = SkinId(hero.index, id)
                val entries = skinMirror[hero.name] as? JsonArray
                val matched = entries?.singleOrNull { number((it as? JsonObject)?.get("Key")) == id }
                val problem = choiceProblem(root, nodes, account, catalog, hero, skin = id)
                if (number((matched as? JsonObject)?.get("Value")) != 1) skipped += "${hero.name} / 皮肤 #$id：镜像不一致，已跳过"
                else if (problem != null) skipped += "${hero.name} / 皮肤 #$id：$problem，已跳过" else {
                    val node = nodes.getValue(key(account, "c${hero.index}_skin$id"))
                    val state = lock(node, "c${hero.index}_skin$id", { integer(it)?.let { value -> value != 1 } == true }, referenceSkins[target]?.toString())
                    if (state == null) skipped += "${hero.name} / 皮肤 #$id：没有已知的皮肤原锁值，已跳过" else skinLocks[target] = state
                }
            }
            val prefix = key(account, "c_${hero.name}_skill_")
            nodes.forEach { (name, node) ->
                if (!name.startsWith(prefix) || !name.endsWith("_unlock")) return@forEach
                val id = name.removePrefix(prefix).removeSuffix("_unlock").toIntOrNull() ?: return@forEach
                if (id <= 0 || integer(node) != 1) return@forEach
                val target = SkillId(hero.index, id)
                val entries = validSkills(skillMirror?.get(hero.name))
                val matched = entries?.singleOrNull { number((it as? JsonObject)?.get("Key")) == id }
                val problem = choiceProblem(root, nodes, account, catalog, hero, skill = id)
                if (bool((matched as? JsonObject)?.get("Value")) != true) skipped += "${hero.name} / 技能 #$id：镜像缺失或不一致，已跳过"
                else if (problem != null) skipped += "${hero.name} / 技能 #$id：$problem，已跳过" else {
                    val suffix = "c_${hero.name}_skill_${id}_unlock"
                    val state = lock(node, suffix, { integer(it) == 0 }, if (suffix in referenceSkills) "0" else null)
                    if (state == null) skipped += "${hero.name} / 技能 #$id：没有已知的技能原锁值，已跳过" else skillLocks[target] = state
                }
            }
        }
        return Prepared(catalog, root, nodes, heroLocks, skinLocks, skillLocks, skipped)
    }

    fun patch(gameBytes: ByteArray?, prefsBytes: ByteArray, account: String, scope: RelockScope,
        originals: List<RelockOriginal> = emptyList(), selection: UnlockSelection, preserveReadingFlags: Boolean = false): UnlockPatch {
        requireNotNull(gameBytes) { "重新锁定需要 game.data 与 XML 同步核对" }
        require(selection.relock) { "未选择重新锁定操作" }
        require(!selection.levels && selection.levelHeroes.isEmpty() && !selection.pets && selection.petIds.isEmpty()) {
            "重新锁定只支持角色、皮肤和技能；等级与宠物需单独操作"
        }
        require(selection.heroes.isNotEmpty() || selection.skins.isNotEmpty() || selection.skills || selection.skillIds.isNotEmpty()) { "请先选择要重新锁定的内容" }
        val prepared = prepare(gameBytes, prefsBytes, account, scope, originals)
        val catalog = prepared.catalog
        val byIndex = catalog.heroes.associateBy { it.index }
        require(selection.heroes.all { it in byIndex } && selection.skins.all { it.skin in byIndex[it.hero]?.skins.orEmpty() } &&
            selection.skillIds.all { it.hero in byIndex && it.skill >= 0 &&
                key(account, "c_${byIndex.getValue(it.hero).name}_skill_${it.skill}_unlock") in prepared.nodes }) { "不能重新锁定未知条目" }
        val heroes = selection.heroes.intersect(prepared.heroLocks.keys)
        val skins = selection.skins.intersect(prepared.skinLocks.keys)
        val skillTargets = if (selection.skills) prepared.skillLocks.keys else selection.skillIds.intersect(prepared.skillLocks.keys)
        var text = UnlockEngine.utf8(prefsBytes)
        val heroMirror = prepared.root.getValue("heroUnlock").jsonObject.toMutableMap()
        val skinMirror = prepared.root.getValue("skinLock").jsonObject.toMutableMap()
        val skillMirror = (prepared.root["heroSkillUnlock"] as? JsonObject)?.toMutableMap()
        val changes = mutableListOf<String>()
        heroes.sorted().forEach { index ->
            val hero = byIndex.getValue(index)
            val state = prepared.heroLocks.getValue(index)
            text = UnlockEngine.replaceXml(text, prepared.nodes.getValue(key(account, "c${index}_unlock")), state.raw)
            heroMirror[hero.name] = JsonPrimitive(false)
            changes += "角色 ${hero.name}：重新锁定（${state.source}：${state.raw}）"
        }
        skins.sortedWith(compareBy({ it.hero }, { it.skin })).forEach { id ->
            val hero = byIndex.getValue(id.hero)
            val state = prepared.skinLocks.getValue(id)
            text = UnlockEngine.replaceXml(text, prepared.nodes.getValue(key(account, "c${id.hero}_skin${id.skin}")), state.raw)
            skinMirror[hero.name] = JsonArray(skinMirror.getValue(hero.name).jsonArray.map {
                if (number(it.jsonObject["Key"]) == id.skin) JsonObject(it.jsonObject + ("Value" to JsonPrimitive(state.raw.toInt()))) else it
            })
            changes += "${hero.name} / 皮肤 #${id.skin}：重新锁定（${state.source}：${state.raw}）"
        }
        skillTargets.sortedWith(compareBy({ it.hero }, { it.skill })).forEach { id ->
            val hero = byIndex.getValue(id.hero)
            val state = prepared.skillLocks.getValue(id)
            text = UnlockEngine.replaceXml(text, prepared.nodes.getValue(key(account, "c_${hero.name}_skill_${id.skill}_unlock")), state.raw)
            skillMirror!![hero.name] = JsonArray(skillMirror.getValue(hero.name).jsonArray.map {
                if (number(it.jsonObject["Key"]) == id.skill) JsonObject(it.jsonObject + ("Value" to JsonPrimitive(false))) else it
            })
            changes += "${hero.name} / 技能 #${id.skill}：重新锁定（${state.source}）"
        }
        val choiceFields = linkedMapOf<String, JsonElement>()
        val currentSkins = (prepared.root["currentSkinDatas"] as? JsonObject)?.toMutableMap()
        val currentSkills = (prepared.root["heroSkill"] as? JsonObject)?.toMutableMap()
        val affected = (heroes + skins.map { it.hero } + skillTargets.map { it.hero }).sorted()
        affected.forEach { index ->
            val hero = byIndex.getValue(index)
            if (index in heroes || skins.any { it.hero == index }) {
                val current = selected(prepared.root, "currentSkinDatas", hero.name)
                if (current != 0 && (index in heroes || SkinId(index, current) in skins)) {
                    currentSkins!![hero.name] = JsonPrimitive(0)
                    choiceFields["currentSkinDatas"] = JsonObject(currentSkins)
                    changes += "${hero.name}：当前皮肤 $current → 基础皮肤 #0"
                }
            }
            if (index in heroes || skillTargets.any { it.hero == index }) {
                val current = selectedSkill(prepared.root, prepared.nodes, account, hero.name)
                if (current != 0 && (index in heroes || SkillId(index, current) in skillTargets)) {
                    currentSkills!![hero.name] = JsonPrimitive(0)
                    choiceFields["heroSkill"] = JsonObject(currentSkills)
                    text = UnlockEngine.replaceXml(text, prepared.nodes.getValue(key(account, "c_${hero.name}_skill")), "0")
                    changes += "${hero.name}：当前技能 $current → 基础技能 #0（同步 XML）"
                }
            }
        }
        if (heroes.isNotEmpty()) {
            val (current, xmlCurrent) = superHeroChoices(prepared.root, prepared.nodes, account)
            if (current in heroes || xmlCurrent in heroes) {
                require(current == xmlCurrent) { "大厅角色 XML 与 game.data 镜像不一致" }
                choiceFields["superHeroInHeroRoom"] = JsonPrimitive(0)
                text = UnlockEngine.replaceXml(text, prepared.nodes.getValue(key(account, "super_hero_in_hero_room")), "0")
                changes += "大厅角色 ${byIndex.getValue(current).name} → 基础角色 #0（同步 XML）"
            }
        }
        val skipped = mutableListOf<String>()
        if (0 in selection.heroes) skipped += "基础角色已保留"
        if (selection.skins.any { it.skin == 0 }) skipped += "基础皮肤已保留"
        if (selection.skillIds.any { it.skill == 0 }) skipped += "基础技能已保留"
        val selectedDescriptions = mutableSetOf<String>()
        (selection.heroes - heroes - 0).forEach { selectedDescriptions += byIndex.getValue(it).name + "：" }
        (selection.skins - skins).filter { it.skin != 0 }.forEach { selectedDescriptions += "${byIndex.getValue(it.hero).name} / 皮肤 #${it.skin}：" }
        (selection.skillIds - skillTargets).filter { it.skill != 0 }.forEach { selectedDescriptions += "${byIndex.getValue(it.hero).name} / 技能 #${it.skill}：" }
        skipped += prepared.skipped.filter { line -> (selection.skills && " / 技能 #" in line) || selectedDescriptions.any { line.startsWith(it) } }
        changes += skipped.distinct()
        val fields = mutableMapOf<String, JsonElement>("heroUnlock" to JsonObject(heroMirror), "skinLock" to JsonObject(skinMirror))
        if (skillMirror != null && skillTargets.isNotEmpty()) fields["heroSkillUnlock"] = JsonObject(skillMirror)
        fields += choiceFields
        val patched = JsonObject(prepared.root + fields)
        val encodedGame = if (patched == prepared.root) gameBytes else UnlockEngine.xor(patched.toString().toByteArray(Charsets.UTF_8))
        val changedPrefs = text.toByteArray(Charsets.UTF_8)
        val (encodedPrefs, flags) = if (!preserveReadingFlags && (heroes.isNotEmpty() || skins.isNotEmpty() || skillTargets.isNotEmpty()))
            UnlockEngine.localReadingFlags(changedPrefs, account) else changedPrefs to emptyList()
        changes += flags
        UnlockEngine.catalog(encodedGame, encodedPrefs, account)
        require(UnlockEngine.game(encodedGame) == patched) { "重新锁定镜像编码复验失败" }
        val checkedNodes = UnlockEngine.prefs(encodedPrefs)
        heroes.forEach { require(roleValue(checkedNodes.getValue(key(account, "c${it}_unlock"))) == false) }
        skins.forEach { require(integer(checkedNodes[key(account, "c${it.hero}_skin${it.skin}")]) == prepared.skinLocks.getValue(it).raw.toInt()) }
        skillTargets.forEach { require(integer(checkedNodes[key(account, "c_${byIndex.getValue(it.hero).name}_skill_${it.skill}_unlock")]) == 0) }
        return UnlockPatch(encodedGame, encodedPrefs, changes)
    }

    // Generated from the anonymized, read-only com.knight.union.bili 8.7.0 sample on 2026-10-23.
    // Only observed locked values are included. In particular c23_skin3 is deliberately absent.
    private val referenceNames = listOf("Knight", "Ranger", "Mage", "Assassin", "Alchemist", "Engineer", "Vampire", "Paladin", "Elves", "Werewolf", "Priest", "Druid", "Robot", "Viking", "Necromancer", "Officer", "Taoist", "Transcendent", "Envoy", "Beheaded", "Ninja", "SpecialForces", "Airbender", "Warlock", "Miner", "TrapMaster", "CostumePrince", "Doctor", "SwordMaster", "Lancer", "Warliege", "ArcaneKnight", "Astrologist", "Fighter", "Joker", "Bard", "Shooter", "AiGirl", "Captain", "YinYang", "GunsExpert", "LadyChef")
    private val referenceSkins: Map<SkinId, Int> = """
        0|1=2000,2=-2,3=5000,4=-3,5=12000,6=0,7=-1,8=0,9=10000,10=8000,11=15000,12=-8,13=8000,14=0,15=0,16=233,17=9999,18=9999,19=-13,20=-5,21=-6,22=0,23=-13,24=12000,25=-6,26=-1,27=10000,28=-13,29=0,30=-19,31=-13,32=0,33=-20,34=-13,35=-6,36=-19,37=12000
        1|1=2000,2=5000,3=5000,4=0,5=0,6=-5,7=0,8=0,9=8000,10=0,11=-8,12=-5,13=0,14=-1,15=-10,16=0,17=-13,18=-1,19=0,20=-16,21=-13,22=0,23=0,24=0,25=-13,26=-13,27=-6,28=-6,29=10000,30=0,31=-13,32=-6,33=0,34=12000
        2|1=2000,2=5000,3=2000,4=2000,5=0,6=0,7=-5,8=8000,9=0,10=8000,11=-4,12=-8,13=10000,14=0,15=0,16=-13,17=0,18=-6,19=0,20=0,21=-13,22=12000,23=0,24=0,25=-13,26=-6,27=0,28=-6,29=-20,30=12000,31=-13,32=0
        3|1=2000,2=5000,3=0,4=2000,5=5000,6=0,7=0,8=0,9=8000,10=0,11=-8,12=0,13=0,14=0,15=-4,16=-10,17=-13,18=-6,19=0,20=0,21=-15,22=-13,23=-5,24=-13,25=-8,26=0,27=-6,28=-13,29=-6,30=-20
        4|1=2000,2=5000,3=0,4=8000,5=0,6=0,7=-1,8=-5,9=8000,10=0,11=12000,12=9999,13=-13,14=-5,15=12000,16=-12,17=-13,18=4000,19=0,20=-13,21=-13,22=12000,23=12000,24=-13,25=12000,26=-6,27=0
        5|1=2000,2=5000,3=0,4=2000,5=5000,6=0,7=0,8=-5,9=6000,10=15000,11=0,12=0,13=0,14=0,15=-8,16=-13,17=-10,18=12000,19=0,20=-13,21=-12,22=-13,23=12000,24=-13,25=-8,26=-19,27=0,28=-13,29=12000,30=-6
        6|1=2000,2=0,3=2000,4=2000,5=5000,6=0,7=-5,8=0,9=0,10=0,11=15000,12=-10,13=0,14=-13,15=-10,16=0,17=-13,18=-6,19=18000,20=-13,21=0,22=-13,23=-13,24=-6,25=-13,26=0
        7|1=2000,2=0,3=5000,4=0,5=-3,6=-4,7=-5,8=-4,9=0,10=0,11=-8,12=-5,13=0,14=0,15=12000,16=9999,17=-13,18=-6,19=0,20=-13,21=12000,22=-13,23=-13,24=18000,25=-13,26=0,27=-6,28=-13,29=0,30=-8
        8|1=2000,2=0,3=0,4=-3,5=0,6=-5,7=0,8=0,9=-5,10=0,11=-4,12=0,13=-10,14=-13,15=0,16=-13,17=-6,18=0,19=0,20=-13,21=0,22=-6,23=0,24=12000,25=-6,26=-13,27=-20
        9|1=2000,2=0,3=2000,4=2000,5=0,6=2000,7=0,8=0,9=-5,10=12000,11=-3,12=8000,13=0,14=0,15=15000,16=0,17=0,18=-13,19=-10,20=-10,21=-13,22=-19,23=-8,24=12000,25=-13,26=-6,27=10000,28=-10,29=11999,30=-6,31=-13,32=0,33=12000
        10|1=5000,2=2000,3=0,4=18000,5=-5,6=8000,7=0,8=6000,9=8000,10=0,11=10000,12=15000,13=-6,14=0,15=-12,16=-13,17=0,18=-13,19=0,20=0,21=-13,22=-6,23=-13,24=0,25=0,26=12000,27=-6,28=-13,29=-20,30=12000
        11|1=5000,2=0,3=5000,4=0,5=0,6=0,7=0,8=15000,9=15000,10=0,11=0,12=0,13=0,14=-10,15=-13,16=12000,17=0,18=-13,19=-6,20=0,21=-13,22=0,23=-6,24=16000,25=-6,26=0,27=12000,28=-13,29=12000
        12|1=5000,2=-1,3=-4,4=0,5=-1,6=-5,7=6000,8=-6,9=15000,10=0,11=8000,12=-5,13=-10,14=12000,15=-13,16=12000,17=-6,18=-13,19=12000,20=-13,21=12000,22=-13,23=12000,24=-13,25=-6,26=0
        13|1=0,2=-4,3=0,4=-5,5=0,6=-5,7=12000,8=-6,9=-1,10=0,11=0,12=10000,13=0,14=15000,15=-6,16=0,17=-13,18=0,19=-5,20=0,21=-13,22=0,23=15000,24=-13,25=-6,26=0,27=12000,28=-6,29=-13,30=-20,31=-10,32=-19
        14|1=-5,2=-7,3=10000,4=0,5=0,6=-5,7=15000,8=-6,9=0,10=-10,11=-13,12=0,13=-8,14=0,15=-13,16=-6,17=0,18=-13,19=-6,20=0,21=12000,22=-6,23=0,24=-13,25=-20,26=0
        15|1=8000,2=0,3=12000,4=-4,5=-5,6=0,7=10000,8=15000,9=12000,10=-13,11=9999,12=-6,13=-10,14=-10,15=-13,16=-10,17=0,18=-13,19=-6,20=0,21=12000,22=-13,23=-6,24=0
        16|1=-6,2=8000,3=0,4=0,5=-5,6=-5,7=0,8=10000,9=0,10=0,11=0,12=-13,13=-10,14=-8,15=-13,16=0,17=-4,18=10000,19=-13,20=0,21=-6,22=16000,23=-13,24=-6,25=12000,26=0
        17|1=0,2=-13,3=-6,4=-13,5=-6,6=-6
        18|1=0,2=-13,3=-13,4=-6
        20|1=0,2=-13,3=-6,4=0,5=-13,6=-6,7=-6
        21|1=-6,2=-13,3=0
        22|1=0,2=-10,3=0,4=-13,5=-10,6=-13,7=12000,8=16000,9=-13,10=15000,11=0,12=-6,13=12000,14=-13,15=-6,16=-6,17=12000
        23|1=0,2=12000,4=-13,5=-10,6=0,7=-13,8=0,9=0,10=-13,11=-6,12=15000,13=0,14=-6,15=-13,16=-6,17=0,18=12000
        24|1=0,2=-13,3=-1,4=-10,5=-1,6=0,7=-13,8=0,9=-10,10=15000,11=18000,12=-13,13=-6,14=-6,15=0,16=12000,17=-13,18=-6,19=0
        25|1=9999,2=-13,3=-10,4=-5,5=-13,6=0,7=18000,8=-13,9=15000,10=-13,11=16000,12=-6,13=-13
        26|1=0,2=9999,3=12000,4=-13,5=-13,6=0,7=-5,8=-10,9=-13,10=-6,11=12000,12=-6,13=0,14=-13,15=-20,16=-6
        27|1=0,2=-13,3=-13,4=0,5=16000,6=-6,7=-13,8=15000,9=-6,10=0,11=-13,12=-6,13=-6
        28|1=0,2=-13,3=-13,4=-6,5=0,6=-13,7=0,8=-6,9=0,10=-6,11=-6,12=-13,13=12000
        29|1=0,2=-13,3=-13,4=-5,5=0,6=-6,7=-13,8=9999,9=-6,10=0,11=-6,12=-6,13=-13,14=12000
        30|1=12000,2=-13,3=-13,4=0,5=0,6=0,7=-13,8=0,9=-6,10=0,11=-6,12=-6,13=-19
        31|1=0,2=-13,3=-10,4=0,5=-13,6=16000,7=-6,8=12000,9=-6,10=12000,11=-6
        32|1=0,2=-13,3=0,4=-13,5=12000,6=-6,7=12000,8=12000,9=-6
        33|1=0,2=-13,3=0,4=-6,5=-13,6=0,7=-6,8=0,9=0,10=-6,11=-13,12=0
        34|1=-19,2=-13,3=-13,4=16000,5=9999,6=-6,7=-6,8=-6,9=0,10=-13
        35|1=0,2=-13,3=-13,4=0,5=-6,6=-6,7=12000,8=-6,9=0,10=-13
        36|1=0,2=-13,3=-13,4=-6,5=-6,6=16000,7=0,8=-13,9=-6
        37|1=0,2=0,3=-13,4=-6,5=-6,6=16000,7=-6,8=12000,9=-13
        38|1=0,2=-13,3=10000,4=-6,5=12000,6=0,7=16000,8=12000,9=-13,10=-6,11=-20
        39|1=0,2=-13,3=0,4=-13,5=-6
        40|1=0,2=-13,3=-19,4=-13,5=-6,6=0
        41|1=-13,2=0,3=-6
    """.trimIndent().lineSequence().flatMap { line ->
        val (hero, entries) = line.split('|', limit = 2)
        entries.split(',').map { token -> val (skin, value) = token.split('='); SkinId(hero.toInt(), skin.toInt()) to value.toInt() }
    }.toMap()
    private val referenceSkills: Set<String> = setOf(
        "c_Airbender_skill_1_unlock",
        "c_Airbender_skill_2_unlock",
        "c_Alchemist_skill_1_unlock",
        "c_Alchemist_skill_2_unlock",
        "c_ArcaneKnight_skill_1_unlock",
        "c_ArcaneKnight_skill_2_unlock",
        "c_Assassin_skill_1_unlock",
        "c_Assassin_skill_2_unlock",
        "c_Astrologist_skill_1_unlock",
        "c_Bard_skill_1_unlock",
        "c_Captain_skill_1_unlock",
        "c_CostumePrince_skill_1_unlock",
        "c_CostumePrince_skill_2_unlock",
        "c_Doctor_skill_1_unlock",
        "c_Doctor_skill_2_unlock",
        "c_Druid_skill_1_unlock",
        "c_Druid_skill_2_unlock",
        "c_Elves_skill_1_unlock",
        "c_Elves_skill_2_unlock",
        "c_Engineer_skill_1_unlock",
        "c_Engineer_skill_2_unlock",
        "c_Fighter_skill_1_unlock",
        "c_Joker_skill_1_unlock",
        "c_Knight_skill_1_unlock",
        "c_Knight_skill_2_unlock",
        "c_Lancer_skill_1_unlock",
        "c_Lancer_skill_2_unlock",
        "c_Mage_skill_1_unlock",
        "c_Mage_skill_2_unlock",
        "c_Miner_skill_1_unlock",
        "c_Miner_skill_2_unlock",
        "c_Necromancer_skill_1_unlock",
        "c_Necromancer_skill_2_unlock",
        "c_Ninja_skill_1_unlock",
        "c_Officer_skill_1_unlock",
        "c_Officer_skill_2_unlock",
        "c_Paladin_skill_1_unlock",
        "c_Paladin_skill_2_unlock",
        "c_Priest_skill_1_unlock",
        "c_Priest_skill_2_unlock",
        "c_Ranger_skill_1_unlock",
        "c_Ranger_skill_2_unlock",
        "c_Robot_skill_1_unlock",
        "c_Robot_skill_2_unlock",
        "c_Shooter_skill_1_unlock",
        "c_SwordMaster_skill_1_unlock",
        "c_SwordMaster_skill_2_unlock",
        "c_Taoist_skill_1_unlock",
        "c_Taoist_skill_2_unlock",
        "c_Transcendent_skill_1_unlock",
        "c_TrapMaster_skill_1_unlock",
        "c_TrapMaster_skill_2_unlock",
        "c_Vampire_skill_1_unlock",
        "c_Vampire_skill_2_unlock",
        "c_Viking_skill_1_unlock",
        "c_Viking_skill_2_unlock",
        "c_Warliege_skill_1_unlock",
        "c_Warliege_skill_2_unlock",
        "c_Warlock_skill_1_unlock",
        "c_Warlock_skill_2_unlock",
        "c_Werewolf_skill_1_unlock",
        "c_Werewolf_skill_2_unlock"
    )
}
