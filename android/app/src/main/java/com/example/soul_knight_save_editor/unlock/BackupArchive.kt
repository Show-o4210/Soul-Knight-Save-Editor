package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupPayload(val packageName: String, val user: Int, val files: List<SaveFile>, val kind: String = "local-save")
data class BackupInfo(val file: File, val packageName: String, val kind: String, val createdAt: Long, val count: Int)

/** Export only. Archives are never imported or deployed to an account by this component. */
object BackupArchive {
    const val MAX_TOTAL_BYTES = 64 * 1024 * 1024
    private val relativePath = Regex("^(files/(?:[A-Za-z0-9_.-]+/)?[A-Za-z0-9_.-]+\\.data|shared_prefs/[A-Za-z0-9_.-]+\\.xml)$")
    private fun relative(payload: BackupPayload, file: SaveFile): String {
        val prefixes = listOf("/data/user/${payload.user}/${payload.packageName}/") +
            if (payload.user == 0) listOf("/data/data/${payload.packageName}/") else emptyList()
        val prefix = prefixes.firstOrNull { file.path.startsWith(it) } ?: error("备份文件不属于所选应用")
        val path = file.path.removePrefix(prefix)
        require(relativePath.matches(path) && ".." !in path && !path.endsWith(".new")) { "备份包含范围外文件" }
        return path
    }

    fun create(directory: File, payload: BackupPayload): BackupInfo {
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(payload.packageName) && payload.user >= 0)
        require(payload.kind in setOf("local-save", "pre-change"))
        require(payload.files.size in 1..128)
        require(payload.files.all { it.bytes.size <= UnlockEngine.MAX_BYTES }) { "单个文件超过当前备份大小限制" }
        require(payload.files.sumOf { it.bytes.size.toLong() } <= MAX_TOTAL_BYTES)
        val paths = payload.files.map { relative(payload, it) }
        require(paths.distinct().size == paths.size) { "备份路径重复" }
        require(directory.isDirectory || directory.mkdirs())
        val now = System.currentTimeMillis()
        val name = "soul-save-$now-${UUID.randomUUID().toString().take(8)}.zip"
        val file = File(directory, name)
        val temporary = File(directory, "$name.pending")
        require(temporary.createNewFile())
        val manifest = buildJsonObject {
            put("schema", 1); put("kind", payload.kind); put("package", payload.packageName)
            put("user", payload.user); put("createdAt", now)
            put("scope", if (payload.kind == "local-save") "local .data files and identified PlayerPrefs; excludes .data.new" else "originals of files changed by this assistant")
            put("files", JsonArray(payload.files.mapIndexed { index, source -> buildJsonObject {
                put("path", paths[index]); put("bytes", source.bytes.size); put("sha256", UnlockEngine.sha(source.bytes))
                put("owner", source.owner); put("mode", source.mode); put("context", source.context)
            } }))
        }
        FileOutputStream(temporary).use { stream ->
            val zip = ZipOutputStream(stream)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            payload.files.forEachIndexed { index, source ->
                zip.putNextEntry(ZipEntry("payload/${paths[index]}")); zip.write(source.bytes); zip.closeEntry()
            }
            zip.finish(); zip.flush(); stream.fd.sync()
        }
        verify(temporary)
        check(temporary.renameTo(file)) { "备份保存未完成，未覆盖任何文件" }
        return BackupInfo(file, payload.packageName, payload.kind, now, payload.files.size)
    }

    fun verify(file: File): BackupInfo {
        var manifest: JsonObject? = null
        val actual = linkedMapOf<String, Pair<Long, String>>()
        var total = 0L
        val names = mutableSetOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name) && names.size <= 129) { "备份条目不合法" }
                val limit = if (entry.name == "manifest.json") 512 * 1024 else UnlockEngine.MAX_BYTES
                if (entry.name != "manifest.json") require(entry.name.startsWith("payload/") && relativePath.matches(entry.name.removePrefix("payload/")) && ".." !in entry.name) { "备份路径不合法" }
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(bytes.size() + n <= limit && total <= MAX_TOTAL_BYTES + 512 * 1024) { "备份超过支持的大小" }
                    bytes.write(buffer, 0, n)
                }
                if (entry.name == "manifest.json") manifest = Json.parseToJsonElement(UnlockEngine.utf8(bytes.toByteArray())).jsonObject
                else actual[entry.name.removePrefix("payload/")] = bytes.size().toLong() to UnlockEngine.sha(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        val metadata = requireNotNull(manifest) { "备份缺少校验清单" }
        require(metadata["schema"]?.jsonPrimitive?.int == 1)
        val expected = metadata.getValue("files").jsonArray
        require(expected.isNotEmpty() && expected.size == actual.size)
        val paths = expected.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        require(paths.distinct().size == paths.size)
        expected.forEach { item ->
            val obj = item.jsonObject
            require(actual[obj.getValue("path").jsonPrimitive.content] == (obj.getValue("bytes").jsonPrimitive.long to obj.getValue("sha256").jsonPrimitive.content)) { "备份校验不一致" }
        }
        return BackupInfo(file, metadata.getValue("package").jsonPrimitive.content, metadata.getValue("kind").jsonPrimitive.content,
            metadata.getValue("createdAt").jsonPrimitive.long, expected.size)
    }
    fun list(directory: File): List<BackupInfo> = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "zip" }
        .mapNotNull { runCatching { verify(it) }.getOrNull() }.sortedByDescending { it.createdAt }
}
