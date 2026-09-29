package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

data class SkinId(val hero: Int, val skin: Int)
data class Hero(val index: Int, val name: String, val unlocked: Boolean?, val skins: Map<Int, Int>)
data class Catalog(val accounts: List<String>, val account: String, val heroes: List<Hero>, val xmlOnly: Boolean)
data class UnlockSelection(val heroes: Set<Int> = emptySet(), val skins: Set<SkinId> = emptySet(),
    val levels: Boolean = false, val skills: Boolean = false)
data class UnlockPatch(val game: ByteArray?, val prefs: ByteArray, val changes: List<String>)

/** Edits only existing, verified local character keys. No generic field editor is exposed. */
object UnlockEngine {
    private val json = Json { isLenient = false }
    private val key = byteArrayOf(115,108,99,122,125,103,117,99,127,87,109,108,107,74,95)
    private val roleKey = Regex("^(?:(\\d+)_)?c(\\d+)_unlock$")
    private val skinKey = Regex("^(?:(\\d+)_)?c(\\d+)_skin(\\d+)$")
    const val MAX_BYTES = 4 * 1024 * 1024

    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun xor(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }
    fun utf8(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "文件超过当前支持的大小，未作修改" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun game(bytes: ByteArray): JsonObject {
        val root = json.parseToJsonElement(utf8(xor(bytes))).jsonObject
        val heroes = root["heroUnlock"] as? JsonObject ?: error("game.data 未识别到角色结构，暂不写入")
        val skins = root["skinLock"] as? JsonObject ?: error("game.data 未识别到皮肤结构，暂不写入")
        require(heroes.isNotEmpty() && heroes.values.all { it is JsonPrimitive && it.booleanOrNull != null }) { "角色格式发生变化，暂不写入" }
        skins.forEach { (name, entries) ->
            require(name in heroes && entries is JsonArray) { "皮肤映射不完整，暂不写入" }
            val ids = entries.jsonArray.map { entry ->
                val obj = entry.jsonObject
                val id = obj["Key"]?.jsonPrimitive?.intOrNull
                require(id != null && id >= 0 && obj["Value"]?.jsonPrimitive?.intOrNull != null) { "皮肤格式发生变化，暂不写入" }
                id
            }
            require(ids.distinct().size == ids.size) { "皮肤编号重复，暂不写入" }
        }
        return root
    }

    fun prefs(bytes: ByteArray): Map<String, Element> {
        val text = utf8(bytes)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "XML 含不支持的外部声明" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        val document = builder.parse(ByteArrayInputStream(bytes))
        require(document.documentElement.tagName == "map") { "未识别为 PlayerPrefs" }
        val result = linkedMapOf<String, Element>()
        val nodes = document.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            require(name.isNotEmpty() && name !in result) { "XML 存在重复或无名称条目，暂不写入" }
            result[name] = node
        }
        return result
    }

    fun recognizesPrefs(bytes: ByteArray): Boolean = runCatching {
        prefs(bytes).keys.any { roleKey.matches(it) || skinKey.matches(it) }
    }.getOrDefault(false)

    private fun value(node: Element) = if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
    private fun bool(node: Element): Boolean = when (value(node).lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> error("角色状态格式未知，暂不写入")
    }
    private fun prefKey(account: String, suffix: String) = if (account.isEmpty()) suffix else "${account}_$suffix"

    fun catalog(gameBytes: ByteArray?, prefsBytes: ByteArray, selectedAccount: String? = null): Catalog {
        val nodes = prefs(prefsBytes)
        val matches = nodes.keys.mapNotNull { roleKey.matchEntire(it) ?: skinKey.matchEntire(it) }
        val accounts = matches.map { it.groupValues[1] }.distinct().sorted()
        require(accounts.isNotEmpty()) { "未找到角色或皮肤特征键；请确认渠道包名和存档已生成" }
        val account = selectedAccount ?: accounts.singleOrNull() ?: error("检测到多个账号，请在专家模式选择账号")
        require(account in accounts) { "选择的账号已不在当前文件中，请重新扫描" }
        // game.data has no proven account identity: never guess when old UID keys coexist.
        require(gameBytes == null || accounts.size == 1) { "多个账号键与 game.data 的对应关系不明确，已保持只读" }
        val root = gameBytes?.let(::game)
        val names = root?.get("heroUnlock")?.jsonObject?.keys?.toList()
        val indexes = matches.filter { it.groupValues[1] == account }.map { it.groupValues[2].toInt() }.distinct().sorted()
        val heroes = indexes.map { index ->
            val unlockNode = nodes[prefKey(account, "c${index}_unlock")]
            if (unlockNode != null) require(unlockNode.tagName in setOf("int", "string", "boolean")) { "角色键类型未支持" }
            val skins = linkedMapOf<Int, Int>()
            nodes.forEach { (name, node) ->
                val match = skinKey.matchEntire(name)
                if (match != null && match.groupValues[1] == account && match.groupValues[2].toInt() == index) {
                    require(node.tagName == "int") { "皮肤键类型未支持" }
                    skins[match.groupValues[3].toInt()] = value(node).toIntOrNull() ?: error("皮肤值不是整数")
                }
            }
            if (names != null) require(index in names.indices) { "XML 与 game.data 角色编号不一致，暂不写入" }
            Hero(index, names?.get(index) ?: "角色 #$index", unlockNode?.let(::bool), skins)
        }
        return Catalog(accounts, account, heroes, gameBytes == null)
    }

    fun accounts(prefsBytes: ByteArray): List<String> = prefs(prefsBytes).keys
        .mapNotNull { roleKey.matchEntire(it) ?: skinKey.matchEntire(it) }.map { it.groupValues[1] }.distinct().sorted()

    /** Preserve XML byte layout except for existing, validated scalar tokens. */
    private fun replaceXml(text: String, node: Element, replacement: String): String {
        val tag = node.tagName
        val name = Regex.escape(node.getAttribute("name"))
        val pattern = Regex("(?s)<$tag\\b[^>]*\\bname\\s*=\\s*[\"']$name[\"'][^>]*(?:/>|>.*?</$tag\\s*>)")
        val found = pattern.findAll(text).toList()
        require(found.size == 1) { "XML 目标定位不唯一，未作修改" }
        val match = found.single()
        val token = match.value
        val updated = if (node.hasAttribute("value")) {
            val attr = Regex("\\bvalue\\s*=\\s*([\"'])(.*?)\\1").find(token) ?: error("XML 属性定位失败")
            val range = attr.groups[2]!!.range
            token.replaceRange(range, replacement)
        } else {
            require(tag == "string") { "不支持的 XML 文本状态" }
            token.substringBefore('>') + ">" + replacement + "</$tag>"
        }
        return text.replaceRange(match.range, updated)
    }

    fun unlock(gameBytes: ByteArray?, prefsBytes: ByteArray, account: String, selection: UnlockSelection): UnlockPatch {
        val catalog = catalog(gameBytes, prefsBytes, account)
        require(selection.heroes.isNotEmpty() || selection.skins.isNotEmpty() || selection.levels || selection.skills) { "请先选择要修改的内容" }
        require(gameBytes != null || (!selection.levels && !selection.skills)) { "等级和技能需要 game.data 与 XML 双文件核对，当前为 XML 单文件模式" }
        val nodes = prefs(prefsBytes)
        val byIndex = catalog.heroes.associateBy { it.index }
        var text = utf8(prefsBytes)
        val root = gameBytes?.let(::game)
        val gameHeroes = root?.get("heroUnlock")?.jsonObject?.toMutableMap()
        val gameSkins = root?.get("skinLock")?.jsonObject?.toMutableMap()
        val gameLevels = if (selection.levels) (root?.get("heroLevel") as? JsonObject)?.toMutableMap()
            ?: error("game.data 缺少角色等级结构，暂不写入") else null
        val gameSkills = if (selection.skills) (root?.get("heroSkillUnlock") as? JsonObject)?.toMutableMap()
            ?: error("game.data 缺少技能结构，暂不写入") else null
        if (selection.levels || selection.skills) require(catalog.heroes.map { it.name }.toSet() == gameHeroes!!.keys) {
            "XML 角色目录与 game.data 不完整对应，暂不写入"
        }
        val changes = mutableListOf<String>()
        selection.heroes.sorted().forEach { index ->
            val hero = byIndex[index] ?: error("角色不存在，不能创建未知条目")
            require(hero.unlocked != null) { "该角色未提供可识别的解锁键" }
            val node = nodes.getValue(prefKey(account, "c${index}_unlock"))
            if (gameHeroes != null) require(gameHeroes[hero.name]?.jsonPrimitive?.booleanOrNull == hero.unlocked) { "角色镜像不一致，请重新确认本地基线" }
            if (!hero.unlocked) {
                text = replaceXml(text, node, if (node.tagName == "int") "1" else "true")
                gameHeroes?.set(hero.name, JsonPrimitive(true))
                changes += "角色 ${hero.name}：解锁"
            }
        }
        selection.skins.sortedWith(compareBy({ it.hero }, { it.skin })).forEach { id ->
            val hero = byIndex[id.hero] ?: error("角色不存在")
            val old = hero.skins[id.skin] ?: error("皮肤不存在，不能创建未知条目")
            var entries: JsonArray? = null
            if (gameSkins != null) {
                entries = gameSkins[hero.name] as? JsonArray ?: error("皮肤镜像缺失")
                val entry = entries.singleOrNull { it.jsonObject["Key"]?.jsonPrimitive?.intOrNull == id.skin } ?: error("皮肤镜像编号不一致")
                require(entry.jsonObject["Value"]?.jsonPrimitive?.intOrNull == old) { "皮肤镜像不一致，请重新确认本地基线" }
            }
            if (old != 1) {
                text = replaceXml(text, nodes.getValue(prefKey(account, "c${id.hero}_skin${id.skin}")), "1")
                if (entries != null) gameSkins!![hero.name] = JsonArray(entries.map {
                    if (it.jsonObject["Key"]!!.jsonPrimitive.int == id.skin) JsonObject(it.jsonObject + ("Value" to JsonPrimitive(1))) else it
                })
                changes += "${hero.name} / 皮肤 #${id.skin}：$old → 1"
            }
        }
        if (selection.levels) {
            require(gameLevels!!.keys == gameHeroes!!.keys) { "角色等级集合与角色解锁集合不一致，暂不写入" }
            catalog.heroes.forEach { hero ->
                val old = gameLevels.getValue(hero.name).jsonPrimitive.intOrNull ?: error("角色等级格式未知，暂不写入")
                val node = nodes[prefKey(account, "c${hero.index}_level")] ?: error("XML 缺少 ${hero.name} 等级镜像，暂不写入")
                require(node.tagName == "int" && value(node).toIntOrNull() == old && old >= 0) { "${hero.name} 等级镜像不一致，暂不写入" }
                if (old < 7) {
                    text = replaceXml(text, node, "7")
                    gameLevels[hero.name] = JsonPrimitive(7)
                    changes += "${hero.name}：等级 $old → 7"
                }
            }
        }
        if (selection.skills) {
            require(gameSkills!!.keys == gameHeroes!!.keys) { "技能集合与角色解锁集合不一致，暂不写入" }
            catalog.heroes.forEach { hero ->
                val entries = gameSkills.getValue(hero.name) as? JsonArray ?: error("${hero.name} 技能格式未知，暂不写入")
                val ids = mutableSetOf<Int>()
                val patched = entries.map { entry ->
                    val skill = entry as? JsonObject ?: error("技能条目格式未知，暂不写入")
                    val id = skill["Key"]?.jsonPrimitive?.intOrNull ?: error("技能编号格式未知，暂不写入")
                    val unlocked = skill["Value"]?.jsonPrimitive?.booleanOrNull ?: error("技能状态格式未知，暂不写入")
                    require(id >= 0 && ids.add(id)) { "技能编号重复或无效，暂不写入" }
                    val node = nodes[prefKey(account, "c_${hero.name}_skill_${id}_unlock")]
                        ?: error("XML 缺少 ${hero.name} 技能 #$id 镜像，暂不写入")
                    require(node.tagName == "int" && value(node) == (if (unlocked) "1" else "0")) { "${hero.name} 技能 #$id 镜像不一致，暂不写入" }
                    if (unlocked) entry else {
                        text = replaceXml(text, node, "1")
                        changes += "${hero.name} / 技能 #$id：解锁"
                        JsonObject(skill + ("Value" to JsonPrimitive(true)))
                    }
                }
                gameSkills[hero.name] = JsonArray(patched)
            }
        }
        // Existing local-reading flags only. Never invent account or registration keys.
        for (flag in listOf("OpenRijTest", "OpenNewtonJsonTest")) {
            val name = if (account.isEmpty()) flag else "${flag}_$account"
            val node = nodes[name] ?: continue
            require(node.tagName == "int" && value(node) in setOf("0", "1")) { "读取开关格式未知，暂不写入" }
            if (value(node) == "1") {
                text = replaceXml(text, node, "0")
                changes += "$flag：切换为本地读取（0）"
            }
        }
        val encodedPrefs = text.toByteArray(Charsets.UTF_8)
        prefs(encodedPrefs)
        val encodedGame = root?.let {
            val fields = mutableMapOf<String, JsonElement>("heroUnlock" to JsonObject(gameHeroes!!), "skinLock" to JsonObject(gameSkins!!))
            if (gameLevels != null) fields["heroLevel"] = JsonObject(gameLevels)
            if (gameSkills != null) fields["heroSkillUnlock"] = JsonObject(gameSkills)
            val patched = JsonObject(it + fields)
            if (patched == it) gameBytes else xor(patched.toString().toByteArray(Charsets.UTF_8)).also { bytes -> require(game(bytes) == patched) }
        }
        val verified = catalog(encodedGame, encodedPrefs, account)
        selection.heroes.forEach { index -> require(verified.heroes.single { it.index == index }.unlocked == true) }
        selection.skins.forEach { id -> require(verified.heroes.single { it.index == id.hero }.skins[id.skin] == 1) }
        if (selection.levels || selection.skills) {
            val checkedNodes = prefs(encodedPrefs)
            val checkedRoot = game(encodedGame!!)
            catalog.heroes.forEach { hero ->
                if (selection.levels) {
                    val level = checkedRoot["heroLevel"]!!.jsonObject.getValue(hero.name).jsonPrimitive.int
                    require(level >= 7 && checkedNodes.getValue(prefKey(account, "c${hero.index}_level")).getAttribute("value").toInt() == level)
                }
                if (selection.skills) checkedRoot["heroSkillUnlock"]!!.jsonObject.getValue(hero.name).jsonArray.forEach { entry ->
                    val id = entry.jsonObject.getValue("Key").jsonPrimitive.int
                    require(entry.jsonObject.getValue("Value").jsonPrimitive.boolean &&
                        checkedNodes.getValue(prefKey(account, "c_${hero.name}_skill_${id}_unlock")).getAttribute("value") == "1")
                }
            }
        }
        return UnlockPatch(encodedGame, encodedPrefs, changes)
    }
}
