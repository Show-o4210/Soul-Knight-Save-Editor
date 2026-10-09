package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

/** Only small metadata crosses Binder. Sensitive file bodies and paths never enter diagnostics. */
internal object ShizukuWire {
    const val MAX_REPLY = 196608
    private val backupPath = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+\\.data$")
    private val directory = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+(?:/(?:files|shared_prefs))?$")
    fun target(packageName: String, user: Int) {
        require(SaveLayout.packagePattern.matches(packageName) && user in 0..100000) { "包名或 Android 用户编号无效" }
    }
    fun path(path: String, backupOnly: Boolean = false) {
        require(path.length <= 512 && !path.contains("..") &&
            (SaveLayout.editablePath.matches(path) || backupOnly && backupPath.matches(path))) { "路径不在本地存档范围内" }
    }
    fun directory(path: String, rootOnly: Boolean = false) {
        require(path.length <= 512 && !path.contains("..") && directory.matches(path) &&
            (!rootOnly || !path.endsWith("/files") && !path.endsWith("/shared_prefs"))) { "目录不在目标应用范围内" }
    }
    fun metadata(file: SaveFile): String = buildJsonObject {
        put("path", file.path); put("owner", file.owner); put("mode", file.mode); put("context", file.context)
    }.toString()
    fun file(text: String, bytes: ByteArray): SaveFile {
        require(text.toByteArray().size <= StreamProtocol.MAX_METADATA)
        val value = Json.parseToJsonElement(text).jsonObject
        val path = value.getValue("path").jsonPrimitive.content
        val owner = value.getValue("owner").jsonPrimitive.content
        val mode = value.getValue("mode").jsonPrimitive.content
        val context = value.getValue("context").jsonPrimitive.content
        require(Regex("[0-9]{1,10}:[0-9]{1,10}").matches(owner) && Regex("[0-7]{3,4}").matches(mode) &&
            context.length <= 256 && (context.isEmpty() || Regex("[a-zA-Z0-9_:,.-]+").matches(context))) { "文件元数据无效" }
        return SaveFile(path, bytes, owner, mode, context)
    }
    fun diagnostic(value: AccessDiagnostic?): JsonElement = value?.let { buildJsonObject {
        put("stage", it.stage); put("package", it.packageName?.let(::JsonPrimitive) ?: JsonNull)
        put("user", it.user?.let(::JsonPrimitive) ?: JsonNull); put("identity", it.identity)
        put("exit", it.exitCode?.let(::JsonPrimitive) ?: JsonNull); put("elapsed", it.elapsedMillis)
        put("output", it.mergedOutput.take(1536))
    } } ?: JsonNull
    fun diagnostic(value: JsonElement?): AccessDiagnostic? {
        if (value == null || value == JsonNull) return null
        val obj = value.jsonObject
        return AccessDiagnostic(AccessBackend.SHIZUKU_ROOT, obj.getValue("stage").jsonPrimitive.content,
            obj["package"]?.jsonPrimitive?.contentOrNull, obj["user"]?.jsonPrimitive?.intOrNull,
            obj.getValue("identity").jsonPrimitive.content, obj["exit"]?.jsonPrimitive?.intOrNull,
            obj.getValue("elapsed").jsonPrimitive.long, obj.getValue("output").jsonPrimitive.content.take(1536))
    }
    fun success(value: JsonElement, diagnostic: AccessDiagnostic?) = buildJsonObject {
        put("ok", true); put("value", value); put("diagnostic", diagnostic(diagnostic))
    }.toString().also { require(it.toByteArray().size <= MAX_REPLY) { "目录结果超出传输限制" } }
    data class Reply(val value: JsonElement, val diagnostic: AccessDiagnostic?)
    fun decode(text: String): Reply {
        require(text.toByteArray().size <= MAX_REPLY)
        val result = Json.parseToJsonElement(text).jsonObject
        val diagnostic = diagnostic(result.getValue("diagnostic"))
        if (result.getValue("ok").jsonPrimitive.boolean) return Reply(result.getValue("value"), diagnostic)
        val kind = AccessFailureKind.entries.firstOrNull { it.name == result.getValue("kind").jsonPrimitive.content }
            ?: error("Shizuku 返回的错误分类无效")
        throw AccessFailure(kind, result.getValue("message").jsonPrimitive.content, diagnostic,
            result.getValue("uncertain").jsonPrimitive.boolean)
    }
    fun failure(error: Throwable, diagnostic: AccessDiagnostic?) = buildJsonObject {
        put("ok", false); put("message", sanitize(error.message ?: "Shizuku 文件访问失败"))
        put("kind", (error as? AccessFailure)?.kind?.name ?: AccessFailureKind.IO.name)
        put("uncertain", (error as? AccessFailure)?.uncertainWrite == true)
        put("diagnostic", diagnostic((error as? AccessFailure)?.diagnostic ?: diagnostic))
    }.toString()
    fun sanitize(message: String): String = message.take(4096)
        .replace(Regex("(?:/[^\\s'\"<>]+)+"), "[路径]")
        .replace(Regex("\\b[A-Za-z0-9+/=_-]{24,}\\b"), "[内容已省略]")
        .replace(Regex("\\b[0-9]{3,}\\b"), "[编号]").take(1024)
}
