package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

/** Bind a shard by its account filename, never by its position or by a default-file fallback. */
object EditEngine {
    fun itemAccount(path: String): String? = SaveLayout.account(path, SaveSource.ITEM)
    fun statisticAccount(path: String): String? = SaveLayout.account(path, SaveSource.STATISTIC)
    fun accounts(snapshot: SaveSnapshot): List<String> {
        val prefs = snapshot.prefs?.let { UnlockEngine.accounts(it.bytes) }.orEmpty()
        val items = snapshot.items.mapNotNull { itemAccount(it.path) }
        val statistics = snapshot.statistics.mapNotNull { statisticAccount(it.path) }
        val named = (prefs + items + statistics).filter(String::isNotEmpty).distinct().sorted()
        return if (named.isNotEmpty()) named else (prefs + items + statistics).distinct()
    }
    fun item(snapshot: SaveSnapshot, account: String): SaveFile? {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        val files = snapshot.items.filter { itemAccount(it.path) == account }
        require(files.size <= 1) { "同一账号存在多份物品存档，暂不自动选择" }
        return files.singleOrNull()
    }
    fun statistic(snapshot: SaveSnapshot, account: String): SaveFile? {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        val files = snapshot.statistics.filter { statisticAccount(it.path) == account }
        require(files.size <= 1) { "同一账号存在多份统计存档，暂不自动选择" }
        return files.singleOrNull()
    }
    fun weaponInfo(snapshot: SaveSnapshot, account: String): WeaponInfo {
        val file = requireNotNull(statistic(snapshot, account)) { "没有与所选账号对应的统计存档" }
        val item = requireNotNull(item(snapshot, account)) { "需要同账号的物品分片核对武器目录版本" }
        return WeaponEngine.info(WeaponEngine.inspect(file.bytes, item.bytes))
    }
    fun weaponStates(snapshot: SaveSnapshot, account: String): List<WeaponState> = WeaponEngine.states(WeaponEngine.inspect(
        requireNotNull(statistic(snapshot, account)).bytes, requireNotNull(item(snapshot, account)).bytes))
    fun hasCharacters(selection: UnlockSelection) = selection.heroes.isNotEmpty() || selection.skins.isNotEmpty() || selection.hasProgress || selection.pets || selection.petIds.isNotEmpty()

    fun preview(snapshot: SaveSnapshot, account: String, characters: UnlockSelection, items: ItemSelection, weapons: Boolean = false): SavePlan {
        return preview(snapshot, account, characters, items, WeaponSelection(allPlusEight = weapons))
    }
    fun preview(snapshot: SaveSnapshot, account: String, characters: UnlockSelection, items: ItemSelection, weapons: WeaponSelection): SavePlan {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        require(hasCharacters(characters) || items.hasChanges || weapons.hasChanges) { "请先选择要修改的内容" }
        val outputs = linkedMapOf<String, ByteArray>()
        val changes = mutableListOf<String>()
        val sections = linkedMapOf<String, List<String>>()
        if (hasCharacters(characters)) {
            val prefs = requireNotNull(snapshot.prefs) { "没有可识别的角色 XML" }
            if (characters.heroes.isNotEmpty() || characters.skins.isNotEmpty() || characters.hasProgress) {
                val patch = UnlockEngine.unlock(snapshot.game?.bytes, prefs.bytes, account, characters)
                outputs[prefs.path] = patch.prefs
                snapshot.game?.let { outputs[it.path] = requireNotNull(patch.game) }
                changes += patch.changes
            }
            if (characters.pets || characters.petIds.isNotEmpty()) {
                val patch = PetEngine.unlock(snapshot.game?.let { outputs[it.path] ?: it.bytes }, outputs[prefs.path] ?: prefs.bytes, account, if (characters.pets) null else characters.petIds)
                outputs[prefs.path] = patch.prefs
                snapshot.game?.let { outputs[it.path] = requireNotNull(patch.game) }
                changes += patch.changes
            }
        }
        sections["角色"] = changes.toList()
        val itemStart = changes.size
        if (items.hasChanges) {
            val file = requireNotNull(item(snapshot, account)) { "没有与所选账号对应的物品存档" }
            val patch = ItemEngine.edit(file.bytes, items)
            outputs[file.path] = patch.bytes
            changes += patch.changes
            snapshot.game?.let { game ->
                // itemData is optional. Parsing this object does not require legacy role/skin structures.
                val root = Json.parseToJsonElement(UnlockEngine.utf8(UnlockEngine.xor(outputs[game.path] ?: game.bytes))).jsonObject
                if ("itemData" in root) {
                    require(accounts(snapshot).size == 1 && root["itemData"] == ItemCodec.decode(file.bytes)) {
                        "game.data 的物品镜像与当前账号不一致，暂不写入"
                    }
                    if (root["itemData"] != patch.root) {
                        val updated = JsonObject(root + ("itemData" to patch.root))
                        outputs[game.path] = UnlockEngine.xor(updated.toString().toByteArray(Charsets.UTF_8))
                        changes += "同步已有的 game.data 物品镜像"
                    }
                }
            }
        }
        sections["物品"] = changes.drop(itemStart)
        val weaponStart = changes.size
        if (weapons.hasChanges) {
            val file = requireNotNull(statistic(snapshot, account)) { "没有与所选账号对应的统计存档" }
            val item = requireNotNull(item(snapshot, account)) { "需要同账号的物品分片核对武器目录版本" }
            val patch = WeaponEngine.edit(file.bytes, item.bytes, weapons)
            outputs[file.path] = patch.bytes
            changes += patch.changes
        }
        sections["武器"] = changes.drop(weaponStart)
        if ((items.hasChanges || weapons.hasChanges) && !hasCharacters(characters)) snapshot.prefs?.let { prefs ->
            val (bytes, flags) = UnlockEngine.localReadingFlags(prefs.bytes, account)
            if (flags.isNotEmpty()) outputs[prefs.path] = bytes
            changes += flags
            sections["读取来源"] = flags
        }
        return SavePlan(outputs.filter { (path, bytes) -> !snapshot.files.single { it.path == path }.bytes.contentEquals(bytes) }, changes, sections.filterValues { it.isNotEmpty() })
    }
}
