package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import java.net.URLDecoder

/** File-account IDs and the Chilly UID used by SaveUtil are different on some channels. */
object ReadingFlagScope {
    data class Binding(val scope: String?, val note: String? = null)
    enum class Mode { LEGACY, MODERN, UNKNOWN }

    private val flag = Regex("^(?:OpenRijTest|OpenNewtonJsonTest)(?:_(${SaveAccountId.TOKEN_PATTERN}))?$")
    private val accountToken = Regex("^${SaveAccountId.TOKEN_PATTERN}$")
    private val chillyUid = Regex("^[1-9][0-9]*$")
    private val jsonKey = Regex("(\"(?:[^\"\\\\]|\\\\.)*\")\\s*:")
    private data class IdentityMap(val values: Map<String, String>, val occurrences: Map<String, Int>)

    private fun parseMapping(mapping: Element): IdentityMap {
        require(mapping.tagName == "string")
        val raw = mapping.textContent
        require(raw.length <= 64 * 1024)
        val decoded = if (raw.trimStart().startsWith("{")) raw else URLDecoder.decode(raw, "UTF-8")
        val parsed = Json.parseToJsonElement(decoded) as? JsonObject ?: error("读取开关绑定不是对象")
        require(parsed.size <= 1024)
        val occurrences = jsonKey.findAll(decoded).map { match ->
            (Json.parseToJsonElement(match.groupValues[1]) as JsonPrimitive).content
        }.groupingBy { it }.eachCount()
        val identities = parsed.mapValues { (_, element) ->
            val id = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(id != null && chillyUid.matches(id)) { "读取开关绑定 UID 格式未知" }
            id
        }
        return IdentityMap(identities, occurrences)
    }

    /** Null means no mapping; an empty list means present but unusable, so do not infer accounts from scopes. */
    fun mappedAccounts(nodes: Map<String, Element>): List<String>? {
        val mapping = nodes["AccountSaveSettingsIds"] ?: return null
        return runCatching {
            val parsed = parseMapping(mapping)
            parsed.values.keys.filter { accountToken.matches(it) && parsed.occurrences[it] == 1 }
        }.getOrDefault(emptyList())
    }

    /** Only whole-XML restore may explicitly reset every original scope through this list. */
    fun rawScopes(nodes: Map<String, Element>): Set<String> = nodes.keys.mapNotNull {
        flag.matchEntire(it)?.groupValues?.get(1)
    }.toSet()

    /** Read the selected storage route without changing either switch or inferring an account. */
    fun readingMode(nodes: Map<String, Element>, account: String): Mode {
        val scope = runCatching { bind(nodes, account).scope }.getOrNull() ?: return Mode.UNKNOWN
        val name = if (scope.isEmpty()) "OpenRijTest" else "OpenRijTest_$scope"
        val node = nodes[name]?.takeIf { it.tagName == "int" } ?: return Mode.UNKNOWN
        return when (node.getAttribute("value")) {
            "0" -> Mode.LEGACY
            "1" -> Mode.MODERN
            else -> Mode.UNKNOWN
        }
    }

    /** Never infer ownership from a unique flag, last login ID, or another account's setting. */
    fun bind(nodes: Map<String, Element>, account: String): Binding {
        require(account.isEmpty() || accountToken.matches(account)) { "读取开关的存档账号格式未知" }
        if (account.isEmpty()) return Binding("")
        val mapping = nodes["AccountSaveSettingsIds"]
        if (mapping == null) {
            if (account in rawScopes(nodes) || rawScopes(nodes).isEmpty()) return Binding(account)
            return Binding(null, "当前账号缺少读取开关绑定记录，已保留其他账号的开关")
        }
        val identities = runCatching { parseMapping(mapping).also {
            require((it.occurrences[account] ?: 0) <= 1) { "当前账号的读取开关绑定键重复" }
        } }.getOrElse { return Binding(null, "读取开关账号绑定记录损坏或格式未知，已保留现有开关") }
        val scope = identities.values[account]
            ?: return Binding(null, "当前账号未记录对应的读取开关 UID，已保留现有开关")
        // Native SaveAccountSaveSettingsId permits several file-account aliases for one Chilly UID.
        // The explicit forward mapping identifies that shared range; it is not an ownership guess.
        return Binding(scope)
    }
}
