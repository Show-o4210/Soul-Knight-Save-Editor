package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

/** Immutable private correspondence: external filenames and manifests never choose write paths. */
class FreeEditStore(private val directory: File) {
    private fun folder(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return File(directory, id)
    }
    private fun durable(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    fun save(session: FreeEditSession) {
        require(directory.isDirectory || directory.mkdirs())
        BackupArchive.validate(BackupPayload(session.snapshot.packageName, session.snapshot.user, session.snapshot.files))
        val target = folder(session.id)
        check(target.mkdir()) { "无法保存自由编辑导出记录" }
        val files = session.snapshot.files.mapIndexed { index, file ->
            durable(File(target, "$index.original"), file.bytes)
            buildJsonObject {
                put("path", file.path); put("owner", file.owner); put("mode", file.mode); put("context", file.context)
                put("sha", UnlockEngine.sha(file.bytes))
            }
        }
        val manifest = buildJsonObject {
            put("schema", 1); put("id", session.id); put("directory", session.directory)
            put("package", session.snapshot.packageName); put("user", session.snapshot.user)
            put("files", JsonArray(files))
            put("entries", JsonArray(session.entries.map { entry -> buildJsonObject {
                put("path", entry.path); put("name", entry.name); put("codec", entry.codec.name); put("plainHash", entry.plainHash)
                put("account", entry.account?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
        val pending = File(target, "manifest.pending")
        durable(pending, manifest.toString().toByteArray(Charsets.UTF_8))
        check(pending.renameTo(File(target, "manifest.json"))) { "导出记录保存未完成" }
    }
    fun load(id: String): FreeEditSession {
        val target = folder(id)
        val manifestFile = File(target, "manifest.json")
        require(manifestFile.length() in 1..512 * 1024) { "导出记录不完整" }
        val manifest = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
        require(manifest.getValue("schema").jsonPrimitive.int == 1 && manifest.getValue("id").jsonPrimitive.content == id)
        val metadata = manifest.getValue("files").jsonArray
        require(metadata.size in 1..128)
        var total = 0L
        val files = metadata.mapIndexed { index, item ->
            val obj = item.jsonObject
            val source = File(target, "$index.original")
            require(source.length() in 1..UnlockEngine.MAX_BYTES)
            total += source.length()
            require(total <= BackupArchive.MAX_TOTAL_BYTES)
            val bytes = source.readBytes()
            require(UnlockEngine.sha(bytes) == obj.getValue("sha").jsonPrimitive.content) { "自由编辑原件校验不一致" }
            SaveFile(obj.getValue("path").jsonPrimitive.content, bytes, obj.getValue("owner").jsonPrimitive.content,
                obj.getValue("mode").jsonPrimitive.content, obj.getValue("context").jsonPrimitive.content)
        }
        val payload = BackupPayload(manifest.getValue("package").jsonPrimitive.content, manifest.getValue("user").jsonPrimitive.int, files)
        BackupArchive.validate(payload)
        val prefs = files.firstOrNull { it.path.contains("/shared_prefs/") }
        val snapshot = SaveSnapshot(payload.packageName, payload.user, prefs, null, files.filter { it.path != prefs?.path })
        val entries = manifest.getValue("entries").jsonArray.map { item ->
            val obj = item.jsonObject
            FreeEditEntry(obj.getValue("path").jsonPrimitive.content, obj.getValue("name").jsonPrimitive.content,
                FreeCodec.valueOf(obj.getValue("codec").jsonPrimitive.content), obj.getValue("plainHash").jsonPrimitive.content,
                obj["account"]?.jsonPrimitive?.contentOrNull)
        }
        require(entries.isNotEmpty() && entries.size <= 128 && entries.map { it.path }.distinct().size == entries.size)
        entries.forEach { entry ->
            val original = files.single { it.path == entry.path && FreeEditEngine.exportable(it) }
            require(FreeEditEngine.isXml(entry.path) == (entry.codec == FreeCodec.PREFS_XML)) { "自由编辑文件类型不一致" }
            require(UnlockEngine.sha(FreeEditEngine.plain(original, entry.codec)) == entry.plainHash) { "自由编辑对应关系校验失败" }
        }
        return FreeEditSession(id, manifest.getValue("directory").jsonPrimitive.content, snapshot, entries)
    }
}
