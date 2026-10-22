package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

enum class FreeCodec(val label: String, val extension: String) {
    GAME_JSON("XOR JSON", "json"), ITEM_JSON("物品 DES JSON", "json"), STATISTIC_JSON("统计 DES JSON", "json"),
    PLAIN_JSON("明文 JSON", "json"), RAW("未识别编码 · 原始字节", "bin")
}
data class FreeSource(val path: String, val codec: FreeCodec)
data class FreeEditEntry(val path: String, val name: String, val codec: FreeCodec, val plainHash: String, val account: String?)
data class FreeEditSession(val id: String, val directory: String, val snapshot: SaveSnapshot, val entries: List<FreeEditEntry>)
data class FreeDocument(val id: String, val name: String, val bytes: ByteArray) {
    val hash: String by lazy { UnlockEngine.sha(bytes) }
}
data class FreeCandidate(val document: FreeDocument, val matchedBy: String)
data class FreeMatch(val entry: FreeEditEntry, val candidates: List<FreeCandidate>, val selected: String?) {
    val chosen get() = candidates.firstOrNull { it.document.id == selected }
}
data class FreeImportReview(val matches: List<FreeMatch>, val ignored: List<String>) {
    val unresolved get() = matches.count { it.candidates.isNotEmpty() && it.selected == null }
    val changed get() = matches.count { it.chosen?.document?.hash?.let { hash -> hash != it.entry.plainHash } == true }
    fun select(path: String, id: String?) = copy(matches = matches.map { match ->
        if (match.entry.path != path) match else {
            require(id == null || id == SKIP || match.candidates.any { it.document.id == id })
            match.copy(selected = id)
        }
    })
    companion object { const val SKIP = "[skip]" }
}

/** Only codecs and file correspondence live here. No item catalog, version or progress rules. */
object FreeEditEngine {
    private val readingFlag = Regex("^(?:OpenRijTest|OpenNewtonJsonTest)(?:_(${SaveAccountId.TOKEN_PATTERN}))?$")
    fun accounts(snapshot: SaveSnapshot): List<String> = (snapshot.files.flatMap { file ->
        if (!file.path.contains("/shared_prefs/")) listOfNotNull(shardAccount(file.path)) else {
            val nodes = UnlockEngine.prefs(file.bytes)
            UnlockEngine.accounts(file.bytes) + nodes.keys.mapNotNull { readingFlag.matchEntire(it)?.groupValues?.get(1) }
        }
    }).distinct().sorted()
    private fun shardAccount(path: String) = EditEngine.itemAccount(path) ?: EditEngine.statisticAccount(path) ?: EditEngine.weaponSkinAccount(path)
    private fun root(bytes: ByteArray) = Json.parseToJsonElement(UnlockEngine.utf8(bytes).removePrefix("\uFEFF")).jsonObject
    fun decode(bytes: ByteArray, codec: FreeCodec): JsonObject = when (codec) {
        FreeCodec.GAME_JSON -> root(UnlockEngine.xor(bytes))
        FreeCodec.ITEM_JSON -> ItemCodec.decode(bytes)
        FreeCodec.STATISTIC_JSON -> StatisticCodec.decode(bytes)
        FreeCodec.PLAIN_JSON -> root(bytes)
        FreeCodec.RAW -> error("原始字节没有 JSON 结构")
    }
    fun detect(file: SaveFile): FreeCodec = FreeCodec.entries.filter { it != FreeCodec.RAW }.firstOrNull {
        runCatching { decode(file.bytes, it) }.isSuccess
    } ?: FreeCodec.RAW
    // JsonElement's serializer can coerce integer tokens above ULong to Double. Indent the
    // already validated JSON text instead, preserving every numeric and string token exactly.
    private fun pretty(root: JsonObject): String {
        val compact = root.toString()
        val output = StringBuilder()
        var quoted = false
        var escaped = false
        var depth = 0
        fun newline() { output.append('\n').append("  ".repeat(depth)) }
        compact.forEach { char ->
            if (quoted) {
                output.append(char)
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { quoted = true; output.append(char) }
                '{', '[' -> { output.append(char); depth++; if (depth > 64) return compact; newline() }
                '}', ']' -> { depth--; newline(); output.append(char) }
                ',' -> { output.append(char); newline() }
                ':' -> output.append(": ")
                else -> output.append(char)
            }
            if (output.length > UnlockEngine.MAX_BYTES) return compact
        }
        return output.toString()
    }
    fun plain(file: SaveFile, codec: FreeCodec): ByteArray = if (codec == FreeCodec.RAW) file.bytes.copyOf()
        else pretty(decode(file.bytes, codec)).toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..UnlockEngine.MAX_BYTES) { "解码后的文本过大：${file.path.substringAfterLast('/')}" }
        }
    fun create(snapshot: SaveSnapshot, selected: Set<String>, account: String?, directory: String): Pair<FreeEditSession, List<FreeDocument>> {
        require(selected.isNotEmpty()) { "请先选择要导出的 .data 文件" }
        require(selected.all { path -> snapshot.files.any { it.path == path && path.endsWith(".data") } }) { "导出目标不在当前读取结果中" }
        val accounts = accounts(snapshot)
        require(account == null || account in accounts) { "读取开关账号不在当前存档中" }
        val files = snapshot.files.filter { it.path in selected || it.path.contains("/shared_prefs/") }
        val baseline = SaveSnapshot(snapshot.packageName, snapshot.user, files.firstOrNull { it.path.contains("/shared_prefs/") }, null,
            files.filter { it.path != files.firstOrNull { item -> item.path.contains("/shared_prefs/") }?.path })
        val entries = mutableListOf<FreeEditEntry>()
        val documents = files.filter { it.path in selected }.sortedBy { it.path }.mapIndexed { index, file ->
            val codec = detect(file)
            val bytes = plain(file, codec)
            val boundAccount = shardAccount(file.path) ?: account ?: accounts.singleOrNull()
            require(boundAccount != null || accounts.size <= 1) { "请先选择此批文件对应的读取开关账号" }
            val name = "${index.toString().padStart(3, '0')}_${file.path.substringAfterLast('/')}.${codec.extension}"
            entries += FreeEditEntry(file.path, name, codec, UnlockEngine.sha(bytes), boundAccount)
            FreeDocument(name, name, bytes)
        }
        return FreeEditSession(java.util.UUID.randomUUID().toString(), directory, baseline, entries) to documents
    }

    private fun prefixMatches(name: String, prefix: String): Boolean {
        if (!name.startsWith(prefix, ignoreCase = true)) return false
        return name.length == prefix.length || name[prefix.length] in ". _-(（["
    }
    private fun nameScore(name: String, entry: FreeEditEntry, full: Boolean): Int {
        val prefix = if (full) entry.name.substringBeforeLast('.') else entry.path.substringAfterLast('/')
        return listOf(prefix, prefix.removeSuffix(".data")).filter { prefixMatches(name, it) }.maxOfOrNull { it.length } ?: 0
    }
    private fun named(name: String, entries: List<FreeEditEntry>, full: Boolean): List<FreeEditEntry> {
        val scores = entries.associateWith { nameScore(name, it, full) }
        val best = scores.values.maxOrNull() ?: 0
        return if (best == 0) emptyList() else entries.filter { scores[it] == best }
    }

    fun match(session: FreeEditSession, documents: List<FreeDocument>): FreeImportReview {
        require(documents.size <= 256 && documents.map { it.id }.distinct().size == documents.size) { "导入文件过多或重复" }
        require(documents.all { it.bytes.size in 1..UnlockEngine.MAX_BYTES } && documents.sumOf { it.bytes.size.toLong() } <= BackupArchive.MAX_TOTAL_BYTES) { "导入文件大小超出限制" }
        val matched = session.entries.associateWith { mutableListOf<FreeCandidate>() }
        val ignored = mutableListOf<String>()
        documents.forEach { document ->
            var candidates = session.entries.filter { it.plainHash == document.hash }
            var reason = "哈希"
            if (candidates.size > 1) {
                val named = named(document.name, candidates, true)
                if (named.size == 1) candidates = named
            }
            if (candidates.isEmpty()) {
                reason = "文件名前缀"
                candidates = named(document.name, session.entries, true)
                if (candidates.isEmpty()) candidates = named(document.name, session.entries, false)
            }
            if (candidates.size == 1) matched.getValue(candidates.single()) += FreeCandidate(document, reason)
            else ignored += document.name + if (candidates.size > 1) "（对应关系不唯一）" else "（未匹配）"
        }
        return FreeImportReview(session.entries.map { entry ->
            val candidates = matched.getValue(entry).sortedWith(compareBy<FreeCandidate> { it.document.name != entry.name }.thenBy { it.document.name })
            val changed = candidates.filter { it.document.hash != entry.plainHash }.distinctBy { it.document.hash }
            val chosen = when {
                changed.size == 1 -> changed.single()
                changed.size > 1 -> null
                else -> candidates.firstOrNull()
            }
            FreeMatch(entry, candidates, chosen?.document?.id)
        }, ignored)
    }

    fun plan(session: FreeEditSession, review: FreeImportReview): SavePlan {
        require(review.unresolved == 0) { "多个修改版本无法唯一判断，请先选择使用哪一份" }
        require(review.matches.map { it.entry } == session.entries) { "导入预览不属于当前导出记录" }
        val outputs = linkedMapOf<String, ByteArray>()
        val changes = mutableListOf<String>()
        val affected = mutableSetOf<String>()
        review.matches.forEach { match ->
            val document = match.chosen?.document ?: return@forEach
            if (document.hash == match.entry.plainHash) return@forEach
            val original = session.snapshot.files.single { it.path == match.entry.path }
            val codec = match.entry.codec
            val bytes = if (codec == FreeCodec.RAW) document.bytes else {
                val edited = root(document.bytes)
                if (edited == decode(original.bytes, codec)) original.bytes else when (codec) {
                    FreeCodec.GAME_JSON -> UnlockEngine.xor(edited.toString().toByteArray(Charsets.UTF_8))
                    FreeCodec.ITEM_JSON -> ItemCodec.encode(edited)
                    FreeCodec.STATISTIC_JSON -> StatisticCodec.encode(edited)
                    FreeCodec.PLAIN_JSON -> document.bytes
                    FreeCodec.RAW -> error("unreachable")
                }.also { require(decode(it, codec) == edited) { "重新编码校验失败" } }
            }
            require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "编码后的文件超过大小限制" }
            if (!bytes.contentEquals(original.bytes)) {
                outputs[original.path] = bytes
                match.entry.account?.let(affected::add)
                changes += "${document.name} → ${original.path.substringAfterLast('/')}（${match.chosen!!.matchedBy}匹配，${bytes.size} 字节）"
            }
        }
        val flags = mutableListOf<String>()
        if (outputs.isNotEmpty()) session.snapshot.files.filter { it.path.contains("/shared_prefs/") }.forEach { prefs ->
            var bytes = prefs.bytes
            affected.forEach { account ->
                val result = UnlockEngine.localReadingFlags(bytes, account)
                bytes = result.first
                flags += result.second
            }
            if (!bytes.contentEquals(prefs.bytes)) outputs[prefs.path] = bytes
        }
        return SavePlan(outputs, changes + flags, mapOf("自由编辑文件" to changes, "读取来源" to flags).filterValues { it.isNotEmpty() })
    }
}
