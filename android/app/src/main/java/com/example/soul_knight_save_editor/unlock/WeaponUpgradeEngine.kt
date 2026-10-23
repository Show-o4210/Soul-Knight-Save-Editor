package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

data class WeaponEvolutionSelection(val allMax: Boolean = false, val levels: Map<String, Int> = emptyMap()) {
    val hasChanges get() = allMax || levels.isNotEmpty()
}
data class WeaponEvolutionState(val definition: WeaponEvolutionDefinition, val level: Int?, val missing: Boolean,
    val error: String? = null)
data class WeaponEvolutionInventory(val version: Int, val root: JsonObject, val states: List<WeaponEvolutionState>,
    val unknownWeapons: Int)
data class WeaponUpgradePatch(val bytes: ByteArray, val root: JsonObject, val changes: List<String>)

/** Editing evolution and skins is composable: only Level changes on an existing record. */
object WeaponEvolutionEngine {
    fun inspect(bytes: ByteArray, itemBytes: ByteArray): WeaponEvolutionInventory {
        val version = ItemEngine.inspect(itemBytes).getValue("AppVersion").jsonPrimitive.int
        val definitions = WeaponEvolutionCatalog.forVersion(version)
        val root = ItemCodec.decode(bytes)
        val weapons = requireNotNull(root["weapons"] as? JsonObject) { "未识别到 weapons 进化记录" }
        val states = definitions.map { definition ->
            val missing = definition.id !in weapons
            runCatching {
                if (missing) WeaponEvolutionState(definition, 0, true)
                else {
                    val record = requireNotNull(weapons[definition.id] as? JsonObject) { "武器记录不是对象" }
                    val name = record["Name"] as? JsonPrimitive
                    require(name?.isString == true && name.content == definition.id) { "Name 与武器 ID 不一致" }
                    val level = nonnegative(record, "Level")
                    nonnegative(record, "CurrentSkinIndex")
                    val skins = requireNotNull(record["UnlockedSkins"] as? JsonArray) { "UnlockedSkins 不是列表" }
                    require(skins.all { it is JsonPrimitive && it.isString && it.content.startsWith("${definition.id}_s_") &&
                        it.content.removePrefix("${definition.id}_s_").let { suffix -> suffix.isNotEmpty() && suffix.all { c -> c in '0'..'9' } } }) {
                        "UnlockedSkins 含未知格式或其他武器的皮肤"
                    }
                    WeaponEvolutionState(definition, level, false)
                }
            }.getOrElse { WeaponEvolutionState(definition, null, missing, it.message ?: "武器进化记录格式未知") }
        }
        return WeaponEvolutionInventory(version, root, states, weapons.keys.count { id -> definitions.none { it.id == id } })
    }

    fun edit(bytes: ByteArray, itemBytes: ByteArray, selection: WeaponEvolutionSelection): WeaponUpgradePatch {
        require(selection.hasChanges) { "请先选择武器进化操作" }
        val inventory = inspect(bytes, itemBytes)
        val states = inventory.states.associateBy { it.definition.id }
        require(selection.levels.keys.all { it in states }) { "不能进化当前版本目录之外的武器" }
        require(selection.levels.all { (id, level) -> level in 0..states.getValue(id).definition.maxLevel }) {
            "普通武器仅支持已确认的进化等级 0 / 1"
        }
        val targets = (if (selection.allMax) states.mapValues { it.value.definition.maxLevel } else emptyMap()).toMutableMap()
        selection.levels.forEach { (id, level) -> targets[id] = maxOf(targets[id] ?: 0, level) }
        val weapons = inventory.root.getValue("weapons").jsonObject.toMutableMap()
        val changes = mutableListOf<String>()
        targets.forEach { (id, target) ->
            val state = states.getValue(id)
            require(state.error == null) { "${state.definition.label}：${state.error}" }
            val old = requireNotNull(state.level)
            // Both batch and individual operations preserve higher values, including historical edits.
            if (target > old) {
                val record = (weapons[id] as? JsonObject)?.toMutableMap() ?: linkedMapOf(
                    "Name" to JsonPrimitive(id), "CurrentSkinIndex" to JsonPrimitive(0), "UnlockedSkins" to JsonArray(emptyList()))
                record["Level"] = JsonPrimitive(target)
                weapons[id] = JsonObject(record)
                changes += "${state.definition.label} [$id]：进化等级 $old → $target"
            }
        }
        val root = JsonObject(inventory.root + ("weapons" to JsonObject(weapons)))
        val result = if (root == inventory.root) bytes else ItemCodec.encode(root, bytes)
        val checked = inspect(result, itemBytes).states.associateBy { it.definition.id }
        targets.forEach { (id, target) -> require(checked.getValue(id).level == maxOf(states.getValue(id).level!!, target)) {
            "武器进化编码复验失败"
        } }
        return WeaponUpgradePatch(result, root, changes)
    }

    private fun nonnegative(record: JsonObject, field: String): Int = requireNotNull(
        (record[field] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }) {
        "$field 不是非负整数"
    }
}

data class MythicSelection(val unlockAll: Boolean = false, val maxAll: Boolean = false, val unlock: Set<Int> = emptySet(),
    val levels: Map<Int, Int> = emptyMap()) {
    val hasChanges get() = unlockAll || maxAll || unlock.isNotEmpty() || levels.isNotEmpty()
}
data class MythicState(val definition: MythicDefinition, val level: Int?, val missing: Boolean, val error: String? = null) {
    val unlocked get() = level?.let { it >= 0 } == true
}
data class MythicInventory(val version: Int, val root: JsonObject, val states: List<MythicState>, val unknownWeapons: Int)

object MythicEngine {
    fun inspect(itemBytes: ByteArray): MythicInventory {
        val root = ItemEngine.inspect(itemBytes)
        val version = root.getValue("AppVersion").jsonPrimitive.int
        val definitions = MythicCatalog.forVersion(version)
        val records = (root["mythicWeapons"] ?: JsonArray(emptyList())) as? JsonArray
            ?: throw IllegalArgumentException("mythicWeapons 不是列表，暂不可修改")
        val ids = records.map { entry ->
            val record = requireNotNull(entry as? JsonObject) { "神话武器列表含非对象记录" }
            requireNotNull((record["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }) {
                "神话武器 id 不是非负整数，暂不可修改"
            }
        }
        val states = definitions.map { definition ->
            val positions = ids.indices.filter { ids[it] == definition.id }
            if (positions.isEmpty()) MythicState(definition, -1, true)
            else runCatching {
                require(positions.size == 1) { "神话武器 id 重复，暂不修改" }
                val record = records[positions.single()].jsonObject
                val level = requireNotNull((record["level"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) {
                    "level 不是整数"
                }
                require(level >= -1) { "level 小于已知的未解锁值 -1" }
                MythicState(definition, level, false)
            }.getOrElse { MythicState(definition, null, false, it.message ?: "神话武器记录格式未知") }
        }
        return MythicInventory(version, root, states, ids.count { id -> definitions.none { it.id == id } })
    }

    fun edit(itemBytes: ByteArray, selection: MythicSelection): WeaponUpgradePatch {
        require(selection.hasChanges) { "请先选择神话武器操作" }
        val inventory = inspect(itemBytes)
        val states = inventory.states.associateBy { it.definition.id }
        require((selection.unlock + selection.levels.keys).all { it in states }) { "不能修改目录外的神话武器 id" }
        require(selection.levels.all { (id, level) -> level in 0..states.getValue(id).definition.maxLevel }) {
            "神话武器目标等级请输入 0 至 3；0 表示基础解锁"
        }
        val targets = linkedMapOf<Int, Int>()
        if (selection.unlockAll) states.keys.forEach { targets[it] = 0 }
        selection.unlock.forEach { targets[it] = 0 }
        if (selection.maxAll) states.forEach { (id, state) -> targets[id] = state.definition.maxLevel }
        selection.levels.forEach { (id, level) -> targets[id] = maxOf(targets[id] ?: 0, level) }
        val records = ((inventory.root["mythicWeapons"] ?: JsonArray(emptyList())) as JsonArray).toMutableList()
        val positions = records.mapIndexed { index, entry -> entry.jsonObject.getValue("id").jsonPrimitive.int to index }.toMap()
        val changes = mutableListOf<String>()
        targets.forEach { (id, target) ->
            val state = states.getValue(id)
            require(state.error == null) { "${state.definition.label}：${state.error}" }
            val old = requireNotNull(state.level)
            if (target > old) {
                val position = positions[id]
                val record: MutableMap<String, JsonElement> = if (position == null) linkedMapOf("id" to JsonPrimitive(id)) else records[position].jsonObject.toMutableMap()
                record["level"] = JsonPrimitive(target)
                if (position == null) records += JsonObject(record) else records[position] = JsonObject(record)
                changes += "${state.definition.label} [${state.definition.weapon} / id=$id]：${if (old < 0) "未解锁" else "等级 $old"} → 等级 $target"
            }
        }
        val root = if (changes.isEmpty()) inventory.root else JsonObject(inventory.root + ("mythicWeapons" to JsonArray(records)))
        val result = if (root == inventory.root) itemBytes else ItemCodec.encode(root, itemBytes)
        val checked = inspect(result).states.associateBy { it.definition.id }
        targets.forEach { (id, target) -> require(checked.getValue(id).level == maxOf(states.getValue(id).level!!, target)) {
            "神话武器编码复验失败"
        } }
        return WeaponUpgradePatch(result, root, changes)
    }
}
