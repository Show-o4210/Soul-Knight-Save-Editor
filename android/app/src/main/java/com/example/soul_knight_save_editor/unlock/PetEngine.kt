package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.w3c.dom.Element

data class PetState(val definition: PetDefinition, val unlocked: Boolean)

/** Quick unlock uses only the 8.6.0 mapping and existing XML keys. */
object PetEngine {
    private val allIds = PetCatalog.entries.take(17).map { it.id } + "Hide" + PetCatalog.entries.drop(17).map { it.id }
    private fun prefKey(account: String, index: Int) = (if (account.isEmpty()) "" else "${account}_") + "p${index}_unlock"
    private fun read(node: Element): Boolean {
        require(node.tagName == "string") { "宠物解锁键格式未知，暂不写入" }
        return when (node.textContent.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> error("宠物解锁状态未知，暂不写入")
        }
    }
    private fun gameRoot(bytes: ByteArray?): JsonObject? = bytes?.let {
        Json.parseToJsonElement(UnlockEngine.utf8(UnlockEngine.xor(it))).jsonObject
    }
    private fun gamePets(root: JsonObject?): JsonObject? {
        val value = root?.get("petUnlock") ?: return null
        require(value is JsonObject && value.keys.toList() == allIds) { "petUnlock 与 8.6.0 映射不符，宠物暂不可修改" }
        require(value.values.all { it is JsonPrimitive && !it.isString && it.booleanOrNull != null }) {
            "petUnlock 状态格式未知，暂不写入"
        }
        return value
    }
    fun inspect(gameBytes: ByteArray?, prefsBytes: ByteArray, account: String): List<PetState> {
        val nodes = UnlockEngine.prefs(prefsBytes)
        val mirror = gamePets(gameRoot(gameBytes))
        if (mirror != null) require(UnlockEngine.accounts(prefsBytes) == listOf(account)) {
            "多个账号与 game.data 的宠物对应关系不明，暂不写入"
        }
        val states = PetCatalog.entries.mapNotNull { pet ->
            val node = nodes[prefKey(account, pet.index)] ?: return@mapNotNull null
            val unlocked = read(node)
            if (mirror != null) require(mirror.getValue(pet.id).jsonPrimitive.boolean == unlocked) {
                "${pet.label} 的 XML 与 game.data 状态不一致，暂不写入"
            }
            PetState(pet, unlocked)
        }
        return states
    }
    fun unlock(gameBytes: ByteArray?, prefsBytes: ByteArray, account: String, selected: Set<String>? = null): UnlockPatch {
        val states = inspect(gameBytes, prefsBytes, account)
        require(states.isNotEmpty()) { "没有找到映射表中的宠物 XML 键" }
        require(selected == null || selected.all { id -> states.any { it.definition.id == id } }) { "不能创建未知或缺失的宠物键" }
        val targets = states.filter { selected == null || it.definition.id in selected }
        val nodes = UnlockEngine.prefs(prefsBytes)
        var text = UnlockEngine.utf8(prefsBytes)
        val root = gameRoot(gameBytes)
        val pets = gamePets(root)?.toMutableMap()
        val changes = mutableListOf<String>()
        targets.filterNot { it.unlocked }.forEach { state ->
            val pet = state.definition
            text = UnlockEngine.replaceXml(text, nodes.getValue(prefKey(account, pet.index)), "true")
            pets?.set(pet.id, JsonPrimitive(true))
            changes += "宠物 ${pet.label} [${pet.id}]：解锁"
        }
        val (prefs, flags) = UnlockEngine.localReadingFlags(text.toByteArray(Charsets.UTF_8), account)
        changes += flags
        val updated = if (pets != null) JsonObject(root!! + ("petUnlock" to JsonObject(pets))) else root
        val game = if (updated == null || updated == root) gameBytes else UnlockEngine.xor(updated.toString().toByteArray(Charsets.UTF_8))
        require(inspect(game, prefs, account).filter { state -> targets.any { it.definition.id == state.definition.id } }.all { it.unlocked }) { "宠物复验失败，未作写回" }
        return UnlockPatch(game, prefs, changes)
    }
}
