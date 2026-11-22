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
class WeaponInventory internal constructor(val root: JsonObject, internal val ids: Set<String>, val existingOnly: Boolean)
data class WeaponPatch(val bytes: ByteArray, val root: JsonObject, val changes: List<String>, val updatedIds: Set<String>)

object StatisticCodec {
    private val key = SecretKeySpec(byteArrayOf(99, 114, 115, 116, 49, 0, 0, 0), "DES")
    private val iv = IvParameterSpec(byteArrayOf(65, 104, 98, 111, 111, 108, 0, 0))
    private fun crypt(mode: Int, bytes: ByteArray) = Cipher.getInstance("DES/CBC/PKCS5Padding").run {
        init(mode, key, StatisticCodec.iv); doFinal(bytes)
    }
    private fun decoded(bytes: ByteArray): Pair<JsonObject, ShardEncoding> {
        require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "统计文件大小超出支持范围" }
        return try {
            if (hasLegacyBase64Envelope(bytes)) {
                val text = UnlockEngine.utf8(bytes).filterNot { it in "\r\n\t " }
                Json.parseToJsonElement(UnlockEngine.utf8(crypt(Cipher.DECRYPT_MODE, Base64.decode(text)))).jsonObject to ShardEncoding.DES_BASE64
            } else RijndaelCodec.decode(bytes) to ShardEncoding.RIJNDAEL_JSON
        } catch (error: Exception) { throw IllegalArgumentException("统计存档无法按已知格式解码，未作修改", error) }
    }
    fun decode(bytes: ByteArray): JsonObject = decoded(bytes).first
    fun format(bytes: ByteArray): ShardEncoding = decoded(bytes).second
    fun encode(root: JsonObject): ByteArray {
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= UnlockEngine.MAX_BYTES) { "统计数据过大" }
        return Base64.encodeToByteArray(crypt(Cipher.ENCRYPT_MODE, plain)).also {
            require(it.size <= UnlockEngine.MAX_BYTES) { "统计文件过大" }
            require(decode(it) == root) { "统计编码复验失败" }
        }
    }
    fun encode(root: JsonObject, originalBytes: ByteArray): ByteArray {
        val (original, encoding) = decoded(originalBytes)
        if (root == original) return originalBytes
        return if (encoding == ShardEncoding.RIJNDAEL_JSON) RijndaelCodec.encode(root) else encode(root)
    }
}

/** Obtain counters only: no usage, blueprint, evolution or other eligibility changes. */
object WeaponEngine {
    private fun counters(root: JsonObject): JsonObject = requireNotNull(root["object2ObtainTime"] as? JsonObject) {
        "未识别到 object2ObtainTime 获取次数表，武器功能暂不可用"
    }
    private fun count(values: JsonObject, id: String): Int {
        val value = values[id] ?: return 0
        return requireNotNull(value.nonNegativeInt()) { "$id 获取次数不是非负整数，暂不修改" }
    }
    fun inspect(bytes: ByteArray, itemBytes: ByteArray, restrictToExisting: Boolean = false): WeaponInventory {
        val version = ItemEngine.version(ItemCodec.decode(itemBytes))
        val future = ItemCatalog.isFutureVersion(version)
        require(version in ItemCatalog.SUPPORTED_VERSIONS || future) {
            "当前武器目录适配 ${ItemCatalog.SUPPORTED_VERSION_NAMES}；同账号物品分片的版本未核对，武器功能暂不可用"
        }
        val root = StatisticCodec.decode(bytes)
        val values = counters(root)
        val existingOnly = future || restrictToExisting
        val ids = if (existingOnly) values.keys.filter { it in WeaponCatalog.names && values[it].nonNegativeInt() != null }
            else WeaponCatalog.names.keys + values.keys.filter(WeaponCatalog::isWeaponId)
        return WeaponInventory(root, ids.toSortedSet(), existingOnly)
    }
    fun states(inventory: WeaponInventory): List<WeaponState> {
        val values = counters(inventory.root)
        return inventory.ids.map { id ->
            runCatching { count(values, id) }.fold({ WeaponState(id, it, id !in values) }, { WeaponState(id, null, id !in values, it.message) })
        }
    }
    fun info(inventory: WeaponInventory): WeaponInfo {
        val values = counters(inventory.root)
        return WeaponInfo(inventory.ids.size, inventory.ids.count { it !in values })
    }
    fun addEight(bytes: ByteArray, itemBytes: ByteArray): WeaponPatch = edit(bytes, itemBytes, WeaponSelection(allPlusEight = true))
    fun edit(bytes: ByteArray, itemBytes: ByteArray, selection: WeaponSelection): WeaponPatch {
        require(selection.increments.values.all { it >= 0 }) { "武器仅支持非负增量" }
        require(!selection.allPlusEight || selection.increments.isEmpty()) { "不能混用全量与逐项武器增量" }
        val inventory = inspect(bytes, itemBytes)
        val original = inventory.root
        val originalCounters = counters(original)
        val values = originalCounters.toMutableMap()
        val allowed = inventory.ids
        require(selection.increments.keys.all { it in allowed }) {
            if (inventory.existingOnly) "新版存档中的所选武器缺少已知有效的获取次数记录，暂不修改" else "不能新增未确认的武器 ID"
        }
        require(!selection.allPlusEight || allowed.isNotEmpty()) { "没有可修改的武器获取次数记录" }
        val increments = if (selection.allPlusEight) allowed.associateWith { 8 } else selection.increments.filterValues { it > 0 }
        val changes = increments.toSortedMap().map { (id, delta) ->
            val old = count(originalCounters, id)
            val next = old.toLong() + delta
            require(next <= Int.MAX_VALUE) { "$id 增量会超出整数范围" }
            values[id] = JsonPrimitive(next.toInt())
            "${WeaponCatalog.names[id] ?: id} [$id]：$old → $next" + if (id !in originalCounters) "（新增获取记录）" else ""
        }
        val root = JsonObject(original + ("object2ObtainTime" to JsonObject(values)))
        return WeaponPatch(if (root == original) bytes else StatisticCodec.encode(root, bytes), root, changes, increments.keys)
    }
}
