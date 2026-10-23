package com.example.soul_knight_save_editor.unlock

import org.w3c.dom.Element

data class RelockSourceResult(val originals: List<RelockOriginal>, val sourceCount: Int, val skippedBackups: Int)

/** Local evidence only: same package, Android user, account and PlayerPrefs destination. */
object RelockSources {
    fun load(snapshot: SaveSnapshot, account: String, repository: SaveRepository, archives: List<BackupInfo>): RelockSourceResult {
        val (history, invalid) = repository.relockHistory(snapshot.packageName)
        val sources = history.map { record ->
            record.createdAt to { repository.relockPrefs(record.key.removePrefix("transaction:"), snapshot) }
        } + archives.filter { it.packageName == snapshot.packageName }.map { info ->
            info.createdAt to { BackupArchive.read(info.file) }
        }
        val result = collect(snapshot, account, sources.sortedByDescending { it.first }.map { it.second })
        return result.copy(skippedBackups = result.skippedBackups + invalid)
    }

    /** Merge first known locked value per key so unlimited backup retention cannot retain XML copies in memory. */
    internal fun collect(snapshot: SaveSnapshot, account: String, sources: Iterable<() -> BackupPayload?>): RelockSourceResult {
        val current = snapshot.prefs ?: return RelockSourceResult(emptyList(), 0, 0)
        val liveNodes = UnlockEngine.prefs(current.bytes)
        val prefix = if (account.isEmpty()) "" else "${account}_"
        val skill = Regex("^c_[A-Za-z][A-Za-z0-9]*_skill_\\d+_unlock$")
        val values = linkedMapOf<String, Pair<String, String>>()
        var anchor: Pair<String, String>? = null
        var count = 0
        var invalid = 0
        fun alias(path: String) = if (snapshot.user == 0) path.replaceFirst("/data/user/0/", "/data/data/") else path
        sources.forEach { source ->
            try {
                val payload = source() ?: return@forEach
                BackupArchive.validate(payload)
                if (payload.packageName != snapshot.packageName || payload.user != snapshot.user) return@forEach
                val prefs = payload.files.singleOrNull { alias(it.path) == alias(current.path) } ?: return@forEach
                val nodes = UnlockEngine.prefs(prefs.bytes)
                if (account !in UnlockEngine.accounts(prefs.bytes)) return@forEach
                var contributed = false
                nodes.forEach nodeLoop@{ (name, node) ->
                    val live = liveNodes[name] ?: return@nodeLoop
                    if (node.tagName != live.tagName || !name.startsWith(prefix)) return@nodeLoop
                    val suffix = name.removePrefix(prefix)
                    val value = value(node)
                    if (suffix == "c0_unlock" && node.tagName in setOf("int", "string", "boolean") &&
                        value.lowercase() in setOf("true", "false", "0", "1") && anchor == null)
                        anchor = node.tagName to value
                    val locked = when {
                        Regex("^c\\d+_unlock$").matches(suffix) -> node.tagName in setOf("int", "string", "boolean") && value.lowercase() in setOf("false", "0")
                        Regex("^c\\d+_skin\\d+$").matches(suffix) -> node.tagName == "int" && value.toIntOrNull()?.let { it != 1 } == true
                        skill.matches(suffix) -> node.tagName == "int" && value == "0"
                        else -> false
                    }
                    if (locked && name !in values) {
                        values[name] = node.tagName to value
                        contributed = true
                    }
                }
                if (contributed) count++
            } catch (_: Exception) { invalid++ }
        }
        if (values.isEmpty()) return RelockSourceResult(emptyList(), count, invalid)
        anchor?.let { values.putIfAbsent("${prefix}c0_unlock", it) }
        fun escaped(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><map>")
            values.forEach { (name, entry) ->
                val (tag, value) = entry
                if (tag == "string") append("<string name=\"${escaped(name)}\">${escaped(value)}</string>")
                else append("<$tag name=\"${escaped(name)}\" value=\"${escaped(value)}\" />")
            }
            append("</map>")
        }.toByteArray(Charsets.UTF_8)
        return RelockSourceResult(listOf(RelockOriginal(snapshot.packageName, snapshot.user, account, xml)), count, invalid)
    }

    private fun value(node: Element) = if (node.hasAttribute("value")) node.getAttribute("value") else node.textContent
}
