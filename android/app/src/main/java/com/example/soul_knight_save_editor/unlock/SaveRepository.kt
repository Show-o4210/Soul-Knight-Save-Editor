package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SaveFile(val path: String, val bytes: ByteArray, val owner: String, val mode: String, val context: String)
data class SaveSnapshot(val packageName: String, val user: Int, val prefs: SaveFile?, val game: SaveFile?, val items: List<SaveFile> = emptyList(),
    val statistics: List<SaveFile> = emptyList()) {
    val files get() = listOfNotNull(prefs, game) + items + statistics
}
data class SavePlan(val outputs: Map<String, ByteArray>, val changes: List<String>, val sections: Map<String, List<String>> = emptyMap())
interface DeviceStorage {
    val backendId: String get() = "native-root"
    fun assertIdleForRecovery() {}
    fun stop(packageName: String, user: Int)
    fun read(path: String): SaveFile
    fun replace(file: SaveFile, bytes: ByteArray)
}

/** One serialized writer. A durable journal survives process death between file replacements. */
class SaveRepository(private val directory: File, private val device: DeviceStorage) {
    init { require(directory.exists() || directory.mkdirs()) }
    private fun persist(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(temp).use { it.write(text.toByteArray()); it.fd.sync() }
        check(temp.renameTo(file)) { "无法保存事务状态，已停止写入" }
    }
    private fun writeBytes(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        require(file.readBytes().contentEquals(bytes)) { "备份校验失败" }
    }
    private fun status(folder: File, state: String) {
        // Append-only states avoid platform-dependent replacement of an existing journal.
        // An interrupted partial line is ignored by stateOf; the previous durable state wins.
        FileOutputStream(File(folder, "state"), true).use { it.write(("\n" + state + "\n").toByteArray()); it.fd.sync() }
    }
    private fun stateOf(folder: File): String {
        val file = File(folder, "state")
        if (!file.exists()) return ""
        return file.readText().substringBeforeLast('\n', "").lineSequence().lastOrNull().orEmpty()
    }
    private fun records(): List<File> = directory.listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").exists() }.sortedByDescending { it.name }
    fun pending(): List<String> = records().filter { stateOf(it) !in setOf("complete", "rolled_back", "restored") }.map { it.name }
    fun packageName(id: String): String = manifest(folder(id)).getValue("package").jsonPrimitive.content
    fun backendId(id: String): String {
        val record = folder(id)
        val state = File(record, "state")
        val selected = if (state.exists()) state.readText().substringBeforeLast('\n', "").lineSequence()
            .lastOrNull { it.startsWith("backend:") }?.removePrefix("backend:") else null
        return selected ?: manifest(record)["backend"]?.jsonPrimitive?.content ?: "native-root"
    }
    fun completed(packageName: String? = null): List<String> = records().filter {
        stateOf(it) == "complete" && (packageName == null || manifest(it)["package"]?.jsonPrimitive?.content == packageName)
    }.map { it.name }
    fun exportable(packageName: String? = null): List<String> = records().filter {
        packageName == null || manifest(it)["package"]?.jsonPrimitive?.content == packageName
    }.map { it.name }
    @Synchronized fun originals(id: String): BackupPayload {
        val folder = folder(id)
        val metadata = manifest(folder)
        val files = metadata.getValue("entries").jsonArray.mapIndexed { index, entry -> entryFile(folder, index, entry.jsonObject, "before") }
        return BackupPayload(metadata.getValue("package").jsonPrimitive.content, metadata.getValue("user").jsonPrimitive.int, files, "pre-change")
    }
    private fun folder(id: String): File {
        require(Regex("^[0-9]+-[a-f0-9-]+$").matches(id))
        return File(directory, id).also { require(it.isDirectory) }
    }
    private fun manifest(folder: File) = Json.parseToJsonElement(File(folder, "manifest.json").readText()).jsonObject
    private fun same(a: SaveFile, b: SaveFile) = a.bytes.contentEquals(b.bytes) && a.owner == b.owner && a.mode == b.mode && a.context == b.context

    @Synchronized fun apply(snapshot: SaveSnapshot, patch: UnlockPatch): String {
        val proposed = mutableMapOf(requireNotNull(snapshot.prefs).path to patch.prefs)
        snapshot.game?.let { proposed[it.path] = requireNotNull(patch.game) }
        return apply(snapshot, SavePlan(proposed, patch.changes))
    }

    @Synchronized fun apply(snapshot: SaveSnapshot, plan: SavePlan): String {
        require(pending().isEmpty()) { "有未完成的写回，请先恢复" }
        require(snapshot.files.map { it.path }.distinct().size == snapshot.files.size) { "存档路径重复" }
        val originals = snapshot.files.associate { it.path to it.bytes }
        require(plan.outputs.isNotEmpty() && plan.outputs.keys.all { it in originals }) { "修改目标不在扫描结果中" }
        require(plan.outputs.values.all { it.size in 1..UnlockEngine.MAX_BYTES }) { "修改结果超过文件大小限制" }
        return commit(snapshot, originals + plan.outputs)
    }

    private fun commit(snapshot: SaveSnapshot, proposed: Map<String, ByteArray>): String {
        device.stop(snapshot.packageName, snapshot.user)
        // Recheck every captured file, even an unchanged mirror, after stopping the game.
        snapshot.files.forEach { require(same(it, device.read(it.path))) { "存档已在扫描后变化；请重新扫描，不覆盖新进度" } }
        val changed = snapshot.files.filter { !it.bytes.contentEquals(proposed.getValue(it.path)) }
        require(changed.isNotEmpty()) { "所选内容没有需要写回的变化" }
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val folder = File(directory, id).apply { check(mkdir()) }
        val entries = changed.mapIndexed { index, original ->
            val after = proposed.getValue(original.path)
            writeBytes(File(folder, "$index.before"), original.bytes)
            writeBytes(File(folder, "$index.after"), after)
            buildJsonObject {
                put("path", original.path); put("owner", original.owner); put("mode", original.mode); put("context", original.context)
                put("before", UnlockEngine.sha(original.bytes)); put("after", UnlockEngine.sha(after))
            }
        }
        persist(File(folder, "manifest.json"), buildJsonObject {
            put("schema", 1); put("package", snapshot.packageName); put("user", snapshot.user)
            put("backend", device.backendId)
            snapshot.prefs?.let { put("prefsPath", it.path) }; snapshot.game?.let { put("gamePath", it.path) }
            put("entries", JsonArray(entries))
        }.toString())
        status(folder, "prepared")
        try {
            status(folder, "applying")
            changed.forEach { original ->
                require(same(original, device.read(original.path))) { "写回前检测到文件变化，停止操作" }
                device.replace(original, proposed.getValue(original.path))
                val actual = device.read(original.path)
                require(same(original.copy(bytes = proposed.getValue(original.path)), actual)) { "写回或权限复读不一致" }
            }
            status(folder, "complete")
            return id
        } catch (error: Exception) {
            if (generateSequence<Throwable>(error) { it.cause }.any { it is AccessFailure && it.uncertainWrite }) {
                status(folder, "recovery_required")
                throw IllegalStateException("写回结果尚未确定，备份与事务已保留；请等待通道确认写任务结束后，手动恢复未完成事务（$id）", error)
            }
            val recovery = runCatching { recover(id) }
            if (recovery.isFailure) {
                status(folder, "recovery_required")
                throw IllegalStateException("写回中断，备份已保留；请先恢复未完成事务（$id）", error)
            }
            throw IllegalStateException("写回未完成，已恢复原文件；${error.message}", error)
        }
    }

    private fun entryFile(folder: File, index: Int, entry: JsonObject, side: String): SaveFile {
        val bytes = File(folder, "$index.$side").readBytes()
        require(UnlockEngine.sha(bytes) == entry.getValue(side).jsonPrimitive.content) { "备份哈希不一致，拒绝恢复" }
        return SaveFile(entry.getValue("path").jsonPrimitive.content, bytes, entry.getValue("owner").jsonPrimitive.content,
            entry.getValue("mode").jsonPrimitive.content, entry.getValue("context").jsonPrimitive.content)
    }

    @Synchronized fun recover(id: String) {
        val folder = folder(id)
        val manifest = manifest(folder)
        if (stateOf(folder) !in setOf("complete", "rolled_back", "restored")) {
            require(backendId(id) == device.backendId) { "未完成事务必须使用原存档访问方式恢复" }
        }
        device.assertIdleForRecovery()
        device.stop(manifest.getValue("package").jsonPrimitive.content, manifest.getValue("user").jsonPrimitive.int)
        val entries = manifest.getValue("entries").jsonArray
        // Unknown third-party changes are never overwritten by automatic crash recovery.
        entries.forEachIndexed { index, json ->
            val entry = json.jsonObject
            val before = entryFile(folder, index, entry, "before")
            val after = entryFile(folder, index, entry, "after")
            val live = device.read(before.path)
            require(same(live, before) || same(live, after)) { "检测到事务外的新变化，自动恢复已暂停；请保留备份" }
        }
        // A manual restore may use the selected backend. Persist it before any replacement so
        // an interrupted restore is pending and cannot continue through a different backend.
        if (backendId(id) != device.backendId) {
            // The existing append-only journal also works on hosts where renameTo cannot
            // replace a manifest. A crash after this line already makes the record pending.
            status(folder, "backend:${device.backendId}")
        }
        status(folder, "recovering")
        entries.indices.reversed().forEach { index ->
            val entry = entries[index].jsonObject
            val before = entryFile(folder, index, entry, "before")
            val after = entryFile(folder, index, entry, "after")
            val live = device.read(before.path)
            require(same(live, before) || same(live, after)) { "恢复前检测到事务外的新变化，已停止；请保留备份" }
            if (!same(live, before)) device.replace(before, before.bytes)
            require(same(device.read(before.path), before)) { "恢复复读失败" }
        }
        status(folder, "rolled_back")
    }

    /** Exact rollback only; does not overwrite progress made after the original operation. */
    @Synchronized fun restore(id: String) {
        require(pending().isEmpty()) { "请先恢复未完成事务" }
        val folder = folder(id)
        require(stateOf(folder) == "complete") { "该备份不是可恢复的已完成事务" }
        recover(id)
        status(folder, "restored")
    }
}
