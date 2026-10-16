package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

data class BackupRecord(val key: String, val packageName: String, val createdAt: Long, val count: Int, val kind: String)

/** Labels are independent of immutable backup contents and hashes. Numbers are never reused. */
class BackupCatalog(private val journal: File) {
    private data class Label(val number: Long, val name: String)
    private fun labels(): MutableMap<String, Label> {
        if (!journal.exists()) return linkedMapOf()
        val result = linkedMapOf<String, Label>()
        journal.readText(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
            // A partial tail left by process death is ignored; later appends start on a new line.
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.let {
                result[it.getValue("key").jsonPrimitive.content] = Label(it.getValue("number").jsonPrimitive.long,
                    it.getValue("name").jsonPrimitive.content)
            }
        }
        return result
    }
    private fun append(key: String, label: Label) {
        val parent = requireNotNull(journal.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs())
        val row = buildJsonObject { put("key", key); put("number", label.number); put("name", label.name) }
        FileOutputStream(journal, true).use { it.write("\n$row\n".toByteArray(Charsets.UTF_8)); it.fd.sync() }
    }
    @Synchronized fun names(records: List<BackupRecord>): Map<String, String> {
        val labels = labels()
        var next = (labels.values.maxOfOrNull { it.number } ?: -1L) + 1L
        records.sortedWith(compareBy<BackupRecord> { it.createdAt }.thenBy { it.key }).forEach { record ->
            if (record.key !in labels) {
                val label = Label(next, "存档#${next++}")
                append(record.key, label)
                labels[record.key] = label
            }
        }
        return records.associate { it.key to labels.getValue(it.key).name }
    }
    @Synchronized fun rename(key: String, value: String) {
        val name = value.trim()
        require(name.isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() || it in "\\/:*?\"<>|" }) { "名称须为 1～80 个字符，不能包含换行或文件名特殊字符" }
        val old = labels()[key] ?: error("备份不存在，请刷新列表")
        append(key, old.copy(name = name))
    }
    companion object {
        /** Limit is per game, across manual snapshots and transaction originals; 0 is unlimited. */
        fun excess(records: List<BackupRecord>, limit: Int): List<BackupRecord> {
            require(limit >= 0)
            if (limit == 0) return emptyList()
            return records.groupBy { it.packageName }.values.flatMap {
                it.sortedWith(compareByDescending<BackupRecord> { row -> row.createdAt }.thenByDescending { row -> row.key }).drop(limit)
            }
        }
    }
}
