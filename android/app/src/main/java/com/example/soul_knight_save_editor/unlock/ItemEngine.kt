package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

data class ItemSelection(
    val tapes: Boolean = false, val blueprints: Boolean = false,
    val facilities: Boolean = false, val jewelry: Boolean = false,
    val increments: Map<ItemKey, Int> = emptyMap(), val amounts: Map<ItemKey, Int> = emptyMap(),
    val selected: Set<ItemKey> = emptySet()
) {
    val hasChanges get() = tapes || blueprints || facilities || jewelry || increments.isNotEmpty() || amounts.isNotEmpty() || selected.isNotEmpty()
}

/** Empty text is an explicit no-op; zero is an intentional absolute amount. */
data class ItemChoices(val actions: ItemSelection = ItemSelection(), val amountText: Map<ItemKey, String> = emptyMap(),
    val incrementText: Map<ItemKey, String> = emptyMap(), val marked: Set<ItemKey> = emptySet()) {
    val valid get() = (amountText.values + incrementText.values).all { it.isBlank() || parseAmount(it) != null } &&
        amountText.filterValues { it.isNotBlank() }.keys.intersect(incrementText.filterValues { it.isNotBlank() }.keys).isEmpty()
    val hasChanges get() = actions.hasChanges || amountText.values.any { it.isNotBlank() } || incrementText.values.any { parseAmount(it)?.let { n -> n > 0 } ?: it.isNotBlank() }
    fun selection(): ItemSelection {
        require(valid) { "数量请输入 0 至 2147483647 的整数，留空表示不修改" }
        val amounts = actions.amounts + amountText.filterValues { it.isNotBlank() }.mapValues { parseAmount(it.value)!! }
        val increments = actions.increments + incrementText.filterValues { it.isNotBlank() }.mapValues { parseAmount(it.value)!! }.filterValues { it > 0 }
        require(amounts.keys.intersect(increments.keys).isEmpty()) { "同一物品不能同时设置目标数量与增量" }
        return actions.copy(amounts = amounts, increments = increments)
    }
    companion object {
        fun parseAmount(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    }
}

object ItemDrafts {
    val groups = ItemCatalog.ofKind(ItemKind.QUANTITY).groupBy { it.group }
    /** Compute all targets first; callers publish the new draft only when every target passes. */
    fun step(root: JsonObject, choices: ItemChoices, group: String, undo: Boolean = false): ItemChoices {
        val entries = requireNotNull(groups[group]) { "未知数量分类" }
        val deltas = choices.actions.increments.toMutableMap()
        entries.forEach { entry ->
            val delta = (deltas[entry.key] ?: 0).toLong() + if (undo) -1000 else 1000
            require(delta in 0..Int.MAX_VALUE.toLong() && ItemEngine.quantity(root, entry.key).toLong() + delta <= Int.MAX_VALUE) { "${entry.label} 的数量或待增加量超出范围；本次整组未加入" }
            if (delta == 0L) deltas.remove(entry.key) else deltas[entry.key] = delta.toInt()
        }
        return choices.copy(actions = choices.actions.copy(increments = deltas))
    }
}

data class ItemPatch(val bytes: ByteArray, val root: JsonObject, val changes: List<String>)

object ItemCodec {
    private val key = SecretKeySpec(byteArrayOf(105, 97, 109, 98, 111, 0, 0, 0), "DES")
    private val iv = IvParameterSpec(byteArrayOf(65, 104, 98, 111, 111, 108, 0, 0))
    private fun crypt(mode: Int, bytes: ByteArray) = Cipher.getInstance("DES/CBC/PKCS5Padding").run {
        init(mode, key, ItemCodec.iv); doFinal(bytes)
    }
    fun decode(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..UnlockEngine.MAX_BYTES) { "物品文件大小超出支持范围" }
        val text = UnlockEngine.utf8(bytes).filterNot { it in "\r\n\t " }
        return try {
            Json.parseToJsonElement(UnlockEngine.utf8(crypt(Cipher.DECRYPT_MODE, Base64.decode(text)))).jsonObject
        } catch (error: Exception) { throw IllegalArgumentException("物品存档无法按已知格式解码，未作修改", error) }
    }
    fun encode(root: JsonObject): ByteArray {
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= UnlockEngine.MAX_BYTES) { "物品数据过大" }
        return Base64.encodeToByteArray(crypt(Cipher.ENCRYPT_MODE, plain)).also {
            require(it.size <= UnlockEngine.MAX_BYTES) { "物品文件过大" }
            require(decode(it) == root) { "物品编码复验失败" }
        }
    }
}

object ItemEngine {
    fun inspect(bytes: ByteArray): JsonObject = ItemCodec.decode(bytes).also { root ->
        val version = root["AppVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == ItemCatalog.VERSION) {
            "当前物品目录适配 8.6.0；此存档版本未核对，物品功能暂不可用"
        }
    }
    fun quantity(root: JsonObject, key: ItemKey): Int {
        val field = root[key.field] ?: return 0
        require(field is JsonObject) { "${key.field} 结构未知，暂不修改" }
        val value = field[key.id] ?: return 0
        val number = value as? JsonPrimitive
        val amount = number?.takeUnless { it.isString }?.intOrNull
        require(amount != null && amount >= 0) { "${key.id} 不是非负整数，暂不修改" }
        return amount
    }
    fun edit(bytes: ByteArray, selection: ItemSelection): ItemPatch {
        require(selection.hasChanges) { "请先选择物品操作" }
        require(selection.increments.keys.intersect(selection.amounts.keys).isEmpty()) { "同一物品不能同时设置目标数量与增量" }
        selection.selected.forEach { key -> require(ItemCatalog.byKey[key]?.kind in setOf(ItemKind.TAPE, ItemKind.BLUEPRINT, ItemKind.FACILITY, ItemKind.JEWELRY)) { "不支持该物品的解锁操作" } }
        fun chosen(kind: ItemKind, all: Boolean) = ItemCatalog.ofKind(kind).filter { all || it.key in selection.selected }
        val original = inspect(bytes)
        val result = original.toMutableMap()
        val changes = mutableListOf<String>()
        fun objectField(field: String): MutableMap<String, JsonElement> {
            val value = result[field] ?: return linkedMapOf()
            require(value is JsonObject) { "$field 结构未知，暂不修改" }
            return value.toMutableMap()
        }
        fun setAmount(entry: ItemDefinition, amount: Int) {
            val old = quantity(original, entry.key)
            val values = objectField(entry.key.field)
            if (old != amount || entry.key.id !in values) {
                values[entry.key.id] = JsonPrimitive(amount)
                result[entry.key.field] = JsonObject(values)
                changes += "${entry.label} [${entry.key.id}]：$old → $amount"
            }
        }
        chosen(ItemKind.TAPE, selection.tapes).forEach { entry ->
            // Historical edited saves may contain larger counts; the selected action normalizes to one.
            setAmount(entry, 1)
        }
        selection.increments.forEach { (key, delta) ->
            val entry = ItemCatalog.byKey[key]
            require(entry?.kind == ItemKind.QUANTITY && delta > 0) { "不支持该物品的增量操作" }
            val next = quantity(original, key).toLong() + delta
            require(next <= Int.MAX_VALUE) { "${entry!!.label} 增量会超出整数范围，请减少次数" }
            setAmount(entry!!, next.toInt())
        }
        selection.amounts.forEach { (key, amount) ->
            val entry = ItemCatalog.byKey[key]
            require(entry?.kind in setOf(ItemKind.AMOUNT, ItemKind.QUANTITY) && amount >= 0) { "不支持该物品的直接数量操作" }
            setAmount(entry!!, amount)
        }
        if (chosen(ItemKind.BLUEPRINT, selection.blueprints).isNotEmpty()) {
            val values = objectField("blueprints")
            chosen(ItemKind.BLUEPRINT, selection.blueprints).forEach { entry ->
                val old = values[entry.key.id]
                require(old == null || old is JsonPrimitive && old.isString && old.content in setOf("Got", "Researched")) {
                    "${entry.key.id} 蓝图状态未知，暂不修改"
                }
                if (old?.jsonPrimitive?.content != "Researched") {
                    values[entry.key.id] = JsonPrimitive("Researched")
                    changes += "${entry.label} [${entry.key.id}]：已研究"
                }
            }
            result["blueprints"] = JsonObject(values)
        }
        if (chosen(ItemKind.FACILITY, selection.facilities).isNotEmpty()) {
            val old = result["itemUnlock"] ?: JsonArray(emptyList())
            require(old is JsonArray && old.all { it is JsonPrimitive && it.isString }) { "设施列表格式未知" }
            val entries = old.toMutableList()
            chosen(ItemKind.FACILITY, selection.facilities).forEach { entry ->
                val value = JsonPrimitive(entry.key.id)
                if (value !in entries) {
                    entries += value
                    changes += "${entry.label} [${entry.key.id}]：加入设施列表"
                }
            }
            result["itemUnlock"] = JsonArray(entries)
        }
        if (chosen(ItemKind.JEWELRY, selection.jewelry).isNotEmpty()) {
            val values = objectField("jewelryData")
            chosen(ItemKind.JEWELRY, selection.jewelry).forEach { entry ->
                val old = values[entry.key.id]
                require(old == null || old is JsonObject) { "${entry.key.id} 饰品结构未知" }
                val fields = (old as? JsonObject)?.toMutableMap() ?: linkedMapOf()
                val item = fields["item"]
                require(item == null || item == JsonPrimitive(entry.key.id)) { "饰品 ID 不一致" }
                fields["item"] = JsonPrimitive(entry.key.id)
                fields["durability"] = JsonPrimitive(100)
                for (rune in listOf("rune1", "rune2")) {
                    val value = fields[rune]
                    require(value == null || value is JsonPrimitive && value.isString) { "饰品符文格式未知" }
                    if (value == null) fields[rune] = JsonPrimitive("None")
                }
                val updated = JsonObject(fields)
                if (updated != old) {
                    values[entry.key.id] = updated
                    changes += "${entry.label} [${entry.key.id}]：饰品充能 100"
                }
            }
            result["jewelryData"] = JsonObject(values)
            for (field in listOf("jewelryPlayerData", "jewelryBlueprints")) {
                // Preserve all existing equipped relationships and blueprint records, including unknown formats.
                if (field !in result) {
                    result[field] = JsonArray(emptyList())
                    changes += "$field：缺失，初始化为空列表"
                }
            }
        }
        val root = JsonObject(result)
        return ItemPatch(if (root == original) bytes else ItemCodec.encode(root), root, changes)
    }
}
