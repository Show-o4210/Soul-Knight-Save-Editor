package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

data class WeaponSkinSelection(val unlockAll: Boolean = false, val unlock: Set<String> = emptySet(),
    val current: Map<String, Int> = emptyMap()) {
    val hasChanges get() = unlockAll || unlock.isNotEmpty() || current.isNotEmpty()
}

data class WeaponSkinState(val weapon: String, val label: String, val skins: List<WeaponSkinDefinition>,
    val current: Int?, val unlocked: Set<String>, val missing: Boolean, val level: Int?, val error: String? = null)

data class WeaponSkinInventory(val version: Int, val root: JsonObject, val states: List<WeaponSkinState>, val unknownWeapons: Int)
data class WeaponSkinPatch(val bytes: ByteArray, val root: JsonObject, val changes: List<String>)

/** DES-iambo or native Rijndael shard. Unlocking never changes evolution level or the current skin. */
object WeaponSkinEngine {
    private fun number(record: JsonObject, field: String): Int {
        val value = (record[field] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(value != null && value >= 0) { "$field 不是非负整数，暂不修改这把武器" }
        return value
    }
    fun inspect(bytes: ByteArray, itemBytes: ByteArray): WeaponSkinInventory {
        val version = ItemEngine.inspect(itemBytes).getValue("AppVersion").jsonPrimitive.int
        val definitions = WeaponSkinCatalog.forVersion(version)
        val root = try { ItemCodec.decode(bytes) }
            catch (error: Exception) { throw IllegalArgumentException("武器皮肤分片无法按已知格式解码", error) }
        val weapons = requireNotNull(root["weapons"] as? JsonObject) { "未识别到 weapons 皮肤记录，暂不可修改" }
        val groups = definitions.groupBy { it.weapon }
        val states = groups.map { (id, skins) ->
            val missing = id !in weapons
            runCatching {
                if (missing) WeaponSkinState(id, skins.first().weaponLabel, skins, 0, emptySet(), true, 0)
                else {
                    val record = requireNotNull(weapons[id] as? JsonObject) { "武器记录不是对象" }
                    val name = record["Name"] as? JsonPrimitive
                    require(name?.isString == true && name.content == id) { "Name 与武器 ID 不一致" }
                    val level = number(record, "Level")
                    val current = number(record, "CurrentSkinIndex")
                    val unlocked = requireNotNull(record["UnlockedSkins"] as? JsonArray) { "UnlockedSkins 不是列表" }
                    require(unlocked.all { it is JsonPrimitive && it.isString && it.content.startsWith("${id}_s_") &&
                        it.content.removePrefix("${id}_s_").let { suffix -> suffix.isNotEmpty() && suffix.all { c -> c in '0'..'9' } } }) {
                        "UnlockedSkins 含未知格式或其他武器的皮肤"
                    }
                    WeaponSkinState(id, skins.first().weaponLabel, skins, current, unlocked.map { it.jsonPrimitive.content }.toSet(), false, level)
                }
            }.getOrElse { error -> WeaponSkinState(id, skins.first().weaponLabel, skins, null, emptySet(), missing, null,
                error.message ?: "记录格式未知") }
        }
        return WeaponSkinInventory(version, root, states, weapons.keys.count { it !in groups })
    }

    /** Explicit UI action only; ties retain the current skin, otherwise use the lowest known index. */
    fun highestAvailable(state: WeaponSkinState, selection: WeaponSkinSelection): Int? {
        if (state.error != null) return null
        val available = state.skins.filter { selection.unlockAll || it.id in state.unlocked || it.id in selection.unlock }
        val highest = available.maxOfOrNull { it.rarity } ?: return null
        val best = available.filter { it.rarity == highest }
        val current = selection.current[state.weapon] ?: state.current
        return best.firstOrNull { it.index == current }?.index ?: best.minOf { it.index }
    }

    fun edit(bytes: ByteArray, itemBytes: ByteArray, selection: WeaponSkinSelection): WeaponSkinPatch {
        require(selection.hasChanges) { "请先选择武器皮肤操作" }
        val inventory = inspect(bytes, itemBytes)
        val definitions = inventory.states.flatMap { it.skins }.associateBy { it.id }
        val states = inventory.states.associateBy { it.weapon }
        require(selection.unlock.all { it in definitions }) { "不能解锁当前版本目录之外的武器皮肤" }
        require(selection.current.keys.all { it in states }) { "不能设置未知武器的皮肤" }
        val unlock = if (selection.unlockAll) definitions.keys else selection.unlock
        val targets = (unlock.map { definitions.getValue(it).weapon } + selection.current.keys).toSet()
        val weapons = inventory.root.getValue("weapons").jsonObject.toMutableMap()
        val changes = mutableListOf<String>()
        targets.forEach { id ->
            val state = states.getValue(id)
            require(state.error == null) { "${state.label}：${state.error}" }
            val record = (weapons[id] as? JsonObject)?.toMutableMap() ?: linkedMapOf(
                "Name" to JsonPrimitive(id), "Level" to JsonPrimitive(0),
                "CurrentSkinIndex" to JsonPrimitive(0), "UnlockedSkins" to JsonArray(emptyList()))
            val skins = record.getValue("UnlockedSkins").jsonArray.toMutableList()
            var changed = false
            state.skins.filter { it.id in unlock && it.id !in state.unlocked }.forEach { skin ->
                skins += JsonPrimitive(skin.id)
                changes += "${state.label} · ${skin.label} [${skin.id}]：解锁"
                changed = true
            }
            record["UnlockedSkins"] = JsonArray(skins)
            selection.current[id]?.let { index ->
                val skin = if (index == 0) null else state.skins.singleOrNull { it.index == index }
                require(index == 0 || skin != null) { "${state.label} 的皮肤索引未核对" }
                require(skin == null || JsonPrimitive(skin.id) in skins) { "请先解锁或同时勾选解锁 ${skin!!.label}" }
                if (index != state.current) {
                    record["CurrentSkinIndex"] = JsonPrimitive(index)
                    changes += "${state.label} [$id]：当前皮肤 ${state.current} → $index（${skin?.label ?: "原皮肤"}）"
                    changed = true
                }
            }
            if (changed) weapons[id] = JsonObject(record)
        }
        val root = JsonObject(inventory.root + ("weapons" to JsonObject(weapons)))
        val result = if (root == inventory.root) bytes else ItemCodec.encode(root, bytes)
        val checked = inspect(result, itemBytes)
        targets.forEach { id ->
            val state = checked.states.single { it.weapon == id }
            require(state.error == null && state.unlocked.containsAll(unlock.filter { definitions.getValue(it).weapon == id }) &&
                (selection.current[id] == null || selection.current[id] == state.current)) { "武器皮肤编码复验失败" }
        }
        return WeaponSkinPatch(result, root, changes)
    }
}
