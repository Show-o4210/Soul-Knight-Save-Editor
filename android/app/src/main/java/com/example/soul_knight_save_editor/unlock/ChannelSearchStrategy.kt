package com.example.soul_knight_save_editor.unlock

import java.util.Collections

/** A bounded filename query, independent of Root implementation and save encoding. */
class SaveSearchQuery(val directory: String, val depth: Int, globs: List<String>) {
    val globs: List<String> = Collections.unmodifiableList(globs.toList())
    private val names: List<Regex>
    init {
        require(directory in setOf("files", "shared_prefs"))
        require(depth in 1..2 && (directory != "shared_prefs" || depth == 1))
        require(this.globs.size in 1..16 && this.globs.distinct().size == this.globs.size)
        require(this.globs.all { it.length in 1..128 && Regex("[a-zA-Z0-9_.*-]+").matches(it) })
        names = this.globs.map { glob -> Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }) }
    }
    fun matchesFilename(name: String): Boolean = names.any { it.matches(name) }
    fun containsPath(root: String, path: String): Boolean {
        val prefix = "$root/$directory/"
        if (!path.startsWith(prefix) || path.contains("..")) return false
        val parts = path.removePrefix(prefix).split('/')
        return parts.size <= depth && parts.all { Regex("[a-zA-Z0-9_.-]+").matches(it) }
    }
    /** Fixed find grammar only; a strategy cannot supply arbitrary shell syntax. */
    fun findCommand(root: String): String {
        val match = Regex("^/data/user/([0-9]+)/([^/]+)$").matchEntire(root)
            ?: throw IllegalArgumentException("扫描根目录无效")
        require(SaveLayout.packagePattern.matches(match.groupValues[2])) { "扫描根目录无效" }
        val terms = globs.joinToString(" -o ") { "-name '$it'" }
        val expression = if (globs.size == 1) terms else "\\( $terms \\)"
        return "find '$root/$directory' -maxdepth $depth -type f $expression"
    }
}

/** Each channel owns its tables. Recognition syntax, codecs and access safety stay shared. */
class ChannelSearchStrategy(val id: String, sources: Map<SaveSource, SaveSearchQuery>,
    val discoveryPrefs: SaveSearchQuery, val discoveryData: SaveSearchQuery, val backupData: SaveSearchQuery) {
    val sources: Map<SaveSource, SaveSearchQuery> = Collections.unmodifiableMap(LinkedHashMap(sources))
    init {
        require(id.isNotBlank() && this.sources.keys == SaveSource.entries.toSet())
        require(this.sources.all { (source, query) -> query.directory == if (source == SaveSource.PREFS) "shared_prefs" else "files" })
        require(discoveryPrefs.directory == "shared_prefs" && discoveryData.directory == "files" && backupData.directory == "files")
    }
    fun source(source: SaveSource): SaveSearchQuery = sources.getValue(source)
}

object ChannelSearchStrategies {
    fun forPackage(packageName: String): ChannelSearchStrategy {
        require(SaveLayout.packagePattern.matches(packageName)) { "包名无效" }
        return when (packageName) {
            "com.ChillyRoom.DungeonShooter" -> OriginalChannelSearch.strategy
            "com.liangwu.yuanqiqishi.vivo" -> VivoChannelSearch.strategy
            "com.knight.union.bili" -> BiliChannelSearch.strategy
            else -> GenericChannelSearch.strategy
        }
    }
}
