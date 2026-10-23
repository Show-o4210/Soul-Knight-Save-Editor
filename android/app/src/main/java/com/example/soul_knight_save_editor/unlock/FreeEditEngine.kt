package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

enum class FreeCodec(val label: String, val extension: String) {
    GAME_JSON("XOR JSON", "json"), ITEM_JSON("物品 JSON · 保留原编码", "json"), STATISTIC_JSON("统计 JSON · 保留原编码", "json"),
    PLAIN_JSON("明文 JSON", "json"), RAW("未识别编码 · 原始字节", "bin"),
    PREFS_XML("XML 配置 · 保留原文", "xml")
}
data class FreeSource(val path: String, val codec: FreeCodec)
data class FreeEditEntry(val path: String, val name: String, val codec: FreeCodec, val plainHash: String, val account: String?)
data class FreeEditSession(val id: String, val directory: String, val snapshot: SaveSnapshot, val entries: List<FreeEditEntry>,
    val keepReadingModes: Boolean = false)
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
    fun isXml(path: String) = path.contains("/shared_prefs/") && path.endsWith(".xml")
    fun exportable(file: SaveFile) = file.path.endsWith(".data") || file.path.endsWith(".data.new") || isXml(file.path)
    private fun xmlAccounts(bytes: ByteArray): List<String> {
        val nodes = UnlockEngine.prefs(bytes)
        val mapped = ReadingFlagScope.mappedAccounts(nodes)
        val extra = mapped ?: nodes.keys.mapNotNull { readingFlag.matchEntire(it)?.groupValues?.get(1) }
        return (UnlockEngine.accounts(bytes) + extra).distinct()
    }
    fun accounts(snapshot: SaveSnapshot): List<String> = (snapshot.files.flatMap { file ->
        if (!file.path.contains("/shared_prefs/")) listOfNotNull(shardAccount(file.path)) else {
            runCatching { xmlAccounts(file.bytes) }.getOrDefault(emptyList())
        }
    }).distinct().sorted()
    private fun shardAccount(path: String) = EditEngine.itemAccount(path) ?: EditEngine.statisticAccount(path) ?: EditEngine.weaponSkinAccount(path)
    private fun pairedPath(path: String): String? = when {
        path.endsWith(".data.new") -> path.removeSuffix(".new")
        path.endsWith(".data") -> "$path.new"
        else -> null
    }
    private fun root(bytes: ByteArray) = Json.parseToJsonElement(UnlockEngine.utf8(bytes).removePrefix("\uFEFF")).jsonObject
    fun decode(bytes: ByteArray, codec: FreeCodec): JsonObject = when (codec) {
        FreeCodec.GAME_JSON -> root(UnlockEngine.xor(bytes))
        FreeCodec.ITEM_JSON -> ItemCodec.decode(bytes)
        FreeCodec.STATISTIC_JSON -> StatisticCodec.decode(bytes)
        FreeCodec.PLAIN_JSON -> root(bytes)
        FreeCodec.RAW -> error("原始字节没有 JSON 结构")
        FreeCodec.PREFS_XML -> error("XML 配置没有 JSON 结构")
    }
    fun detect(file: SaveFile): FreeCodec = if (isXml(file.path)) FreeCodec.PREFS_XML else FreeCodec.entries.filter {
        it != FreeCodec.RAW && it != FreeCodec.PREFS_XML
    }.firstOrNull {
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
    private fun validateXml(bytes: ByteArray) {
        require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "XML 文件为空或超过大小限制" }
        try { UnlockEngine.prefs(bytes) }
        catch (error: Exception) { throw IllegalArgumentException("XML 配置格式无效：${error.message}", error) }
    }
    fun plain(file: SaveFile, codec: FreeCodec): ByteArray = when (codec) {
        FreeCodec.RAW -> file.bytes.copyOf()
        FreeCodec.PREFS_XML -> file.bytes.copyOf().also(::validateXml)
        else -> pretty(decode(file.bytes, codec)).toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..UnlockEngine.MAX_BYTES) { "解码后的文本过大：${file.path.substringAfterLast('/')}" }
        }
    }
    fun create(snapshot: SaveSnapshot, selected: Set<String>, account: String?, directory: String): Pair<FreeEditSession, List<FreeDocument>> {
        require(selected.isNotEmpty()) { "请先选择要导出的 .data、.data.new 或 XML 文件" }
        require(selected.all { path -> snapshot.files.any { it.path == path && exportable(it) } }) { "导出目标不在当前读取结果中" }
        BackupArchive.validate(BackupPayload(snapshot.packageName, snapshot.user, snapshot.files))
        val accounts = accounts(snapshot)
        require(account == null || account in accounts) { "读取开关账号不在当前存档中" }
        // Keep an existing native counterpart privately even when only one format is exported.
        val baselinePaths = selected + selected.mapNotNull(::pairedPath)
        val files = snapshot.files.filter { it.path in baselinePaths || it.path.contains("/shared_prefs/") }
        val baseline = SaveSnapshot(snapshot.packageName, snapshot.user, files.firstOrNull { it.path.contains("/shared_prefs/") }, null,
            files.filter { it.path != files.firstOrNull { item -> item.path.contains("/shared_prefs/") }?.path })
        val entries = mutableListOf<FreeEditEntry>()
        val documents = files.filter { it.path in selected }.sortedBy { it.path }.mapIndexed { index, file ->
            val codec = detect(file)
            val bytes = plain(file, codec)
            val boundAccount = if (codec == FreeCodec.PREFS_XML) null else shardAccount(file.path) ?: account ?: accounts.singleOrNull()
            require(codec == FreeCodec.PREFS_XML || boundAccount != null || accounts.size <= 1) { "请先选择此批文件对应的读取开关账号" }
            val suffix = if (codec == FreeCodec.PREFS_XML) "" else ".${codec.extension}"
            val name = "${index.toString().padStart(3, '0')}_${file.path.substringAfterLast('/')}$suffix"
            entries += FreeEditEntry(file.path, name, codec, UnlockEngine.sha(bytes), boundAccount)
            FreeDocument(name, name, bytes)
        }
        // Derive this before filtering the baseline: an XML-only export still belongs to
        // the captured game's new-format loading mode and must not switch it to stale DES.
        val keepReadingModes = snapshot.files.any { it.path.endsWith(".data.new") }
        return FreeEditSession(java.util.UUID.randomUUID().toString(), directory, baseline, entries, keepReadingModes) to documents
    }

    private fun prefixMatches(name: String, prefix: String): Boolean {
        if (!name.startsWith(prefix, ignoreCase = true)) return false
        return name.length == prefix.length || name[prefix.length] in ". _-(（["
    }
    private fun nameScore(name: String, entry: FreeEditEntry, full: Boolean): Int {
        val prefix = if (full) entry.name.substringBeforeLast('.') else entry.path.substringAfterLast('/')
        val withoutExtension = if (entry.codec == FreeCodec.PREFS_XML) prefix.removeSuffix(".xml") else prefix.removeSuffix(".data")
        return listOf(prefix, withoutExtension).filter { prefixMatches(name, it) }.maxOfOrNull { it.length } ?: 0
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
        val jsonEdits = linkedMapOf<String, JsonObject>()
        review.matches.forEach { match ->
            val document = match.chosen?.document ?: return@forEach
            if (document.hash == match.entry.plainHash) return@forEach
            val original = session.snapshot.files.single { it.path == match.entry.path }
            val codec = match.entry.codec
            val bytes = if (codec == FreeCodec.RAW) document.bytes else if (codec == FreeCodec.PREFS_XML) {
                validateXml(document.bytes)
                document.bytes
            } else {
                val edited = root(document.bytes)
                if (codec in setOf(FreeCodec.ITEM_JSON, FreeCodec.STATISTIC_JSON, FreeCodec.PLAIN_JSON)) jsonEdits[original.path] = edited
                if (edited == decode(original.bytes, codec)) original.bytes else when (codec) {
                    FreeCodec.GAME_JSON -> UnlockEngine.xor(edited.toString().toByteArray(Charsets.UTF_8))
                    FreeCodec.ITEM_JSON -> ItemCodec.encode(edited, original.bytes)
                    FreeCodec.STATISTIC_JSON -> StatisticCodec.encode(edited, original.bytes)
                    FreeCodec.PLAIN_JSON -> document.bytes
                    FreeCodec.RAW -> error("unreachable")
                    FreeCodec.PREFS_XML -> error("unreachable")
                }.also { require(decode(it, codec) == edited) { "重新编码校验失败" } }
            }
            require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "编码后的文件超过大小限制" }
            if (!bytes.contentEquals(original.bytes)) {
                outputs[original.path] = bytes
                match.entry.account?.let(affected::add)
                if (codec == FreeCodec.PREFS_XML) {
                    affected += xmlAccounts(original.bytes)
                    affected += xmlAccounts(bytes)
                }
                changes += "${document.name} → ${original.path.substringAfterLast('/')}（${match.chosen!!.matchedBy}匹配，${bytes.size} 字节）"
            }
        }
        syncPairs(session, jsonEdits, outputs, changes, affected)
        val flags = mutableListOf<String>()
        if (outputs.isNotEmpty() && session.keepReadingModes) flags += "来源包含新格式分片：保留读取模式及导入 XML 中显式修改的开关值"
        if (outputs.isNotEmpty() && !session.keepReadingModes) session.snapshot.files.filter { it.path.contains("/shared_prefs/") }.forEach { prefs ->
            // User-edited XML is the base. Applying reading flags to the old original
            // would otherwise silently discard every imported XML change.
            var bytes = outputs[prefs.path] ?: prefs.bytes
            if (affected.isEmpty()) return@forEach
            // Some SDK preference files have no reading flags; keep their exact bytes.
            if (runCatching { xmlAccounts(bytes).isEmpty() }.getOrDefault(true)) return@forEach
            affected.forEach { account ->
                val result = UnlockEngine.localReadingFlags(bytes, account)
                bytes = result.first
                flags += result.second
            }
            if (!bytes.contentEquals(prefs.bytes)) outputs[prefs.path] = bytes else outputs.remove(prefs.path)
        }
        return SavePlan(outputs, changes + flags, mapOf("自由编辑文件" to changes, "读取来源" to flags).filterValues { it.isNotEmpty() })
    }

    private data class Delta(val path: List<String>, val value: JsonElement?)
    private fun deltas(before: JsonObject, after: JsonObject, prefix: List<String> = emptyList()): List<Delta> =
        (before.keys + after.keys).flatMap { key ->
            val old = before[key]
            val next = after[key]
            when {
                old == next -> emptyList()
                old is JsonObject && next is JsonObject -> deltas(old, next, prefix + key)
                else -> listOf(Delta(prefix + key, next)) // Arrays are an explicit whole-value edit.
            }
        }

    private fun applyDelta(root: JsonObject, delta: Delta, depth: Int = 0): JsonObject {
        val field = delta.path[depth]
        val values = root.toMutableMap()
        if (depth == delta.path.lastIndex) {
            if (delta.value == null) values.remove(field) else values[field] = delta.value
        } else {
            val child = values[field]
            if (child == null && delta.value == null) return root
            require(child == null || child is JsonObject) { "对应的新旧分片字段结构不同，无法同步本次自由编辑；请核对两份文件" }
            values[field] = applyDelta(child as? JsonObject ?: JsonObject(emptyMap()), delta, depth + 1)
        }
        return JsonObject(values)
    }

    private fun encoded(root: JsonObject, original: SaveFile, codec: FreeCodec): ByteArray = when (codec) {
        FreeCodec.ITEM_JSON -> ItemCodec.encode(root, original.bytes)
        FreeCodec.STATISTIC_JSON -> StatisticCodec.encode(root, original.bytes)
        FreeCodec.PLAIN_JSON -> root.toString().toByteArray(Charsets.UTF_8)
        else -> error("此编码不支持分片同步")
    }

    /** Apply only exported JSON changes to each captured format; unrelated fields retain their own baseline. */
    private fun syncPairs(session: FreeEditSession, edits: Map<String, JsonObject>, outputs: MutableMap<String, ByteArray>,
        changes: MutableList<String>, affected: MutableSet<String>) {
        val handled = mutableSetOf<String>()
        edits.keys.forEach { path ->
            if (!handled.add(path)) return@forEach
            val paired = pairedPath(path) ?: return@forEach
            val other = session.snapshot.files.singleOrNull { it.path == paired } ?: return@forEach
            handled += paired
            val source = session.snapshot.files.single { it.path == path }
            val pair = listOf(source, other)
            val codecs = pair.associate { it.path to detect(it) }
            if (codecs.values.any { it !in setOf(FreeCodec.ITEM_JSON, FreeCodec.STATISTIC_JSON, FreeCodec.PLAIN_JSON) }) {
                changes += "${other.path.substringAfterLast('/')}：对应编码未识别，已保留原样；请自行核对新旧分片"
                return@forEach
            }
            val original = pair.associate { it.path to decode(it.bytes, codecs.getValue(it.path)) }
            val updates = pair.mapNotNull { file -> edits[file.path]?.let { deltas(original.getValue(file.path), it) } }
            if (updates.size == 2) updates[0].forEach { first -> updates[1].forEach { second ->
                val overlap = first.path == second.path || first.path.take(second.path.size) == second.path || second.path.take(first.path.size) == first.path
                require(!overlap || first == second) { "同一新旧分片的自由编辑内容冲突，请让两份文件的同一字段保持一致，或只导入一份修改" }
            } }
            val combined = updates.flatten().distinct()
            pair.forEach { file ->
                val before = original.getValue(file.path)
                val after = combined.fold(before) { state, delta -> applyDelta(state, delta) }
                if (after == before) outputs.remove(file.path) else {
                    val bytes = encoded(after, file, codecs.getValue(file.path))
                    require(bytes.size in 1..UnlockEngine.MAX_BYTES && decode(bytes, codecs.getValue(file.path)) == after) { "新旧分片同步编码校验失败" }
                    outputs[file.path] = bytes
                    shardAccount(file.path)?.let(affected::add)
                    if (file.path !in edits) changes += "同步已有分片 ${file.path.substringAfterLast('/')}：仅同步本次自由编辑字段，保留其余内容和原编码"
                }
            }
        }
    }
}
