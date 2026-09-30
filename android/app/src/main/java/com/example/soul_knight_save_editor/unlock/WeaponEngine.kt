package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

data class WeaponInfo(val total: Int, val missing: Int)
data class WeaponState(val id: String, val count: Int?, val missing: Boolean, val error: String? = null)
data class WeaponSelection(val allPlusEight: Boolean = false, val increments: Map<String, Int> = emptyMap()) {
    val hasChanges get() = allPlusEight || increments.values.any { it > 0 }
}
data class WeaponChoices(val incrementText: Map<String, String> = emptyMap(), val marked: Set<String> = emptySet()) {
    val valid get() = incrementText.values.all { it.isBlank() || ItemChoices.parseAmount(it) != null }
    val hasChanges get() = incrementText.values.any { ItemChoices.parseAmount(it)?.let { n -> n > 0 } ?: it.isNotBlank() }
    fun selection(): WeaponSelection {
        require(valid) { "武器增量请输入非负整数，留空或 0 不修改" }
        return WeaponSelection(increments = incrementText.filterValues { it.isNotBlank() }.mapValues { ItemChoices.parseAmount(it.value)!! }.filterValues { it > 0 })
    }
}
data class WeaponPatch(val bytes: ByteArray, val root: JsonObject, val changes: List<String>)

object StatisticCodec {
    private val key = SecretKeySpec(byteArrayOf(99, 114, 115, 116, 49, 0, 0, 0), "DES")
    private val iv = IvParameterSpec(byteArrayOf(65, 104, 98, 111, 111, 108, 0, 0))
    private fun crypt(mode: Int, bytes: ByteArray) = Cipher.getInstance("DES/CBC/PKCS5Padding").run {
        init(mode, key, StatisticCodec.iv); doFinal(bytes)
    }
    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "统计文件大小超出支持范围" }
        return try {
            val text = UnlockEngine.utf8(bytes).filterNot { it in "\r\n\t " }
            Json.parseToJsonElement(UnlockEngine.utf8(crypt(Cipher.DECRYPT_MODE, Base64.decode(text)))).jsonObject
        } catch (error: Exception) { throw IllegalArgumentException("统计存档无法按已知格式解码，未作修改", error) }
    }
    fun encode(root: JsonObject): ByteArray {
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= UnlockEngine.MAX_BYTES) { "统计数据过大" }
        return Base64.encodeToByteArray(crypt(Cipher.ENCRYPT_MODE, plain)).also {
            require(it.size <= UnlockEngine.MAX_BYTES) { "统计文件过大" }
            require(decode(it) == root) { "统计编码复验失败" }
        }
    }
}

/** Obtain counters only: no usage, blueprint, evolution or other eligibility changes. */
object WeaponEngine {
    private fun counters(root: JsonObject): JsonObject = requireNotNull(root["object2ObtainTime"] as? JsonObject) {
        "未识别到 object2ObtainTime 获取次数表，武器功能暂不可用"
    }
    private fun ids(values: JsonObject) = (WeaponCatalog.names.keys + values.keys.filter(WeaponCatalog::isWeaponId)).toSortedSet()
    private fun count(values: JsonObject, id: String): Int {
        val value = values[id] ?: return 0
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(number != null && number >= 0) { "$id 获取次数不是非负整数，暂不修改" }
        return number
    }
    fun inspect(bytes: ByteArray, itemBytes: ByteArray): JsonObject {
        val version = ItemCodec.decode(itemBytes)["AppVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == WeaponCatalog.VERSION) {
            "当前武器目录适配 8.6.0；同账号物品分片的版本未核对，武器功能暂不可用"
        }
        return StatisticCodec.decode(bytes).also { counters(it) }
    }
    fun states(root: JsonObject): List<WeaponState> {
        val values = counters(root)
        return ids(values).map { id ->
            runCatching { count(values, id) }.fold({ WeaponState(id, it, id !in values) }, { WeaponState(id, null, id !in values, it.message) })
        }
    }
    fun info(root: JsonObject): WeaponInfo {
        val values = counters(root)
        val ids = ids(values)
        return WeaponInfo(ids.size, ids.count { it !in values })
    }
    fun addEight(bytes: ByteArray, itemBytes: ByteArray): WeaponPatch = edit(bytes, itemBytes, WeaponSelection(allPlusEight = true))
    fun edit(bytes: ByteArray, itemBytes: ByteArray, selection: WeaponSelection): WeaponPatch {
        require(selection.increments.values.all { it >= 0 }) { "武器仅支持非负增量" }
        require(!selection.allPlusEight || selection.increments.isEmpty()) { "不能混用全量与逐项武器增量" }
        val original = inspect(bytes, itemBytes)
        val values = counters(original).toMutableMap()
        val allowed = ids(counters(original))
        require(selection.increments.keys.all { it in allowed }) { "不能新增未确认的武器 ID" }
        val increments = if (selection.allPlusEight) allowed.associateWith { 8 } else selection.increments.filterValues { it > 0 }
        val changes = increments.toSortedMap().map { (id, delta) ->
            val old = count(counters(original), id)
            val next = old.toLong() + delta
            require(next <= Int.MAX_VALUE) { "$id 增量会超出整数范围" }
            values[id] = JsonPrimitive(next.toInt())
            "${WeaponCatalog.names[id] ?: id} [$id]：$old → $next" + if (id !in counters(original)) "（新增获取记录）" else ""
        }
        val root = JsonObject(original + ("object2ObtainTime" to JsonObject(values)))
        return WeaponPatch(if (root == original) bytes else StatisticCodec.encode(root), root, changes)
    }
}
