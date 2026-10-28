package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

/** Bind a shard by its account filename, never by its position or by a default-file fallback. */
object EditEngine {
    fun itemAccount(path: String): String? = SaveLayout.account(path, SaveSource.ITEM)
    fun statisticAccount(path: String): String? = SaveLayout.account(path, SaveSource.STATISTIC)
    fun weaponSkinAccount(path: String): String? = SaveLayout.account(path, SaveSource.WEAPON_SKINS)
    fun accounts(snapshot: SaveSnapshot): List<String> {
        val prefs = snapshot.prefs?.let { UnlockEngine.accounts(it.bytes) }.orEmpty()
        val items = snapshot.items.mapNotNull { itemAccount(it.path) }
        val statistics = snapshot.statistics.mapNotNull { statisticAccount(it.path) }
        val skins = snapshot.weaponSkins.mapNotNull { weaponSkinAccount(it.path) }
        val named = (prefs + items + statistics + skins).filter(String::isNotEmpty).distinct().sorted()
        return if (named.isNotEmpty()) named else (prefs + items + statistics + skins).distinct()
    }
    fun item(snapshot: SaveSnapshot, account: String): SaveFile? {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        return preferred(snapshot, account, snapshot.items.filter { itemAccount(it.path) == account }, "物品", ItemCodec::decode)
    }
    fun statistic(snapshot: SaveSnapshot, account: String): SaveFile? {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        return preferred(snapshot, account, snapshot.statistics.filter { statisticAccount(it.path) == account }, "统计", StatisticCodec::decode)
    }
    fun weaponInfo(snapshot: SaveSnapshot, account: String): WeaponInfo {
        val file = requireNotNull(statistic(snapshot, account)) { "没有与所选账号对应的统计存档" }
        val item = requireNotNull(item(snapshot, account)) { "需要同账号的物品分片核对武器目录版本" }
        return WeaponEngine.info(WeaponEngine.inspect(file.bytes, item.bytes))
    }
    fun weaponStates(snapshot: SaveSnapshot, account: String): List<WeaponState> = WeaponEngine.states(WeaponEngine.inspect(
        requireNotNull(statistic(snapshot, account)).bytes, requireNotNull(item(snapshot, account)).bytes))
    fun weaponSkinFile(snapshot: SaveSnapshot, account: String): SaveFile? {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        return preferred(snapshot, account, snapshot.weaponSkins.filter { weaponSkinAccount(it.path) == account }, "武器皮肤", ItemCodec::decode)
    }
    /** One physical shard may have a legacy and a modern encoding. Other locations stay ambiguous. */
    private fun variants(files: List<SaveFile>, label: String): List<SaveFile> {
        require(files.size <= 2 && files.map { it.path.removeSuffix(".new") }.distinct().size <= 1 &&
            files.map { it.path }.distinct().size == files.size) { "同一账号存在多份${label}存档，暂不自动选择" }
        return files.sortedBy { it.path.endsWith(".new") }
    }
    private fun preferred(snapshot: SaveSnapshot, account: String, files: List<SaveFile>, label: String,
        decode: (ByteArray) -> JsonObject): SaveFile? {
        val choices = variants(files, label)
        if (choices.size < 2) return choices.singleOrNull()
        val mode = snapshot.prefs?.let { ReadingFlagScope.readingMode(UnlockEngine.prefs(it.bytes), account) }
            ?: ReadingFlagScope.Mode.UNKNOWN
        return when (mode) {
            ReadingFlagScope.Mode.LEGACY -> choices.first()
            ReadingFlagScope.Mode.MODERN -> choices.last()
            ReadingFlagScope.Mode.UNKNOWN -> {
                require(decode(choices.first().bytes) == decode(choices.last().bytes)) {
                    "新旧${label}分片内容不同，且无法确认当前账号的格式读取模式；已停止自动选择"
                }
                choices.last()
            }
        }
    }
    private fun hasModernSource(snapshot: SaveSnapshot, account: String) =
        snapshot.items.any { itemAccount(it.path) == account && it.path.endsWith(".data.new") } ||
            snapshot.statistics.any { statisticAccount(it.path) == account && it.path.endsWith(".data.new") } ||
            snapshot.weaponSkins.any { weaponSkinAccount(it.path) == account && it.path.endsWith(".data.new") }
    fun weaponSkinInventory(snapshot: SaveSnapshot, account: String): WeaponSkinInventory = WeaponSkinEngine.inspect(
        requireNotNull(weaponSkinFile(snapshot, account)) { "没有与此账号对应的武器皮肤分片" }.bytes,
        requireNotNull(item(snapshot, account)) { "需要同账号的物品分片核对武器皮肤目录版本" }.bytes)
    fun weaponEvolutionInventory(snapshot: SaveSnapshot, account: String): WeaponEvolutionInventory = WeaponEvolutionEngine.inspect(
        requireNotNull(weaponSkinFile(snapshot, account)) { "没有与此账号对应的武器进化分片" }.bytes,
        requireNotNull(item(snapshot, account)) { "需要同账号的物品分片核对武器进化目录版本" }.bytes)
    fun mythicInventory(snapshot: SaveSnapshot, account: String): MythicInventory = MythicEngine.inspect(
        requireNotNull(item(snapshot, account)) { "没有与此账号对应的物品存档" }.bytes)
    fun hasCharacters(selection: UnlockSelection) = selection.heroes.isNotEmpty() || selection.skins.isNotEmpty() || selection.hasProgress || selection.pets || selection.petIds.isNotEmpty()

    fun preview(snapshot: SaveSnapshot, account: String, characters: UnlockSelection, items: ItemSelection, weapons: Boolean = false,
        dataMode: SaveDataMode? = null): SavePlan {
        return preview(snapshot, account, characters, items, WeaponSelection(allPlusEight = weapons), dataMode = dataMode)
    }
    fun preview(snapshot: SaveSnapshot, account: String, characters: UnlockSelection, items: ItemSelection, weapons: WeaponSelection,
        weaponSkins: WeaponSkinSelection = WeaponSkinSelection(),
        evolution: WeaponEvolutionSelection = WeaponEvolutionSelection(), mythic: MythicSelection = MythicSelection(),
        relockScope: RelockScope? = null, relockOriginals: List<RelockOriginal> = emptyList(),
        dataMode: SaveDataMode? = null): SavePlan {
        require(account in accounts(snapshot)) { "账号不在扫描结果中" }
        val selectedSnapshot = dataMode?.select(snapshot) ?: snapshot
        if (dataMode != null) {
            fun needItem() = require(selectedSnapshot.items.any { itemAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 物品存档，请切换格式或重新读取"
            }
            if (items.hasChanges || mythic.hasChanges || weapons.hasChanges || weaponSkins.hasChanges || evolution.hasChanges) needItem()
            if (weapons.hasChanges) require(selectedSnapshot.statistics.any { statisticAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 统计存档，请切换格式或重新读取"
            }
            if (weaponSkins.hasChanges || evolution.hasChanges) require(selectedSnapshot.weaponSkins.any { weaponSkinAccount(it.path) == account }) {
                "未发现 ${dataMode.extension} 武器进化存档，请切换格式或重新读取"
            }
        }
        if (characters.relock) require(!characters.levels && characters.levelHeroes.isEmpty() && !characters.pets && characters.petIds.isEmpty()) {
            "重新锁定仅处理角色、皮肤和技能；等级与宠物不参与此操作"
        }
        require(hasCharacters(characters) || items.hasChanges || weapons.hasChanges || weaponSkins.hasChanges || evolution.hasChanges || mythic.hasChanges) { "请先选择要修改的内容" }
        val outputs = linkedMapOf<String, ByteArray>()
        val changes = mutableListOf<String>()
        val sections = linkedMapOf<String, List<String>>()
        val preserveReadingFlags = dataMode?.preserveReadingFlags ?: hasModernSource(selectedSnapshot, account)
        if (hasCharacters(characters)) {
            val prefs = requireNotNull(selectedSnapshot.prefs) { "没有可识别的角色 XML" }
            if (characters.heroes.isNotEmpty() || characters.skins.isNotEmpty() || characters.hasProgress) {
                val patch = if (characters.relock) {
                    val scope = requireNotNull(relockScope) { "请重新读取存档以核对重新锁定来源" }
                    require(scope.packageName == selectedSnapshot.packageName && scope.user == selectedSnapshot.user) { "重新锁定来源与当前游戏不一致" }
                    RelockEngine.patch(selectedSnapshot.game?.bytes, prefs.bytes, account, scope, relockOriginals, characters,
                        preserveReadingFlags = preserveReadingFlags)
                } else UnlockEngine.unlock(selectedSnapshot.game?.bytes, prefs.bytes, account, characters,
                    preserveReadingFlags = preserveReadingFlags)
                outputs[prefs.path] = patch.prefs
                selectedSnapshot.game?.let { outputs[it.path] = requireNotNull(patch.game) }
                changes += patch.changes
            }
            if (characters.pets || characters.petIds.isNotEmpty()) {
                val patch = PetEngine.unlock(selectedSnapshot.game?.let { outputs[it.path] ?: it.bytes }, outputs[prefs.path] ?: prefs.bytes, account, if (characters.pets) null else characters.petIds,
                    preserveReadingFlags = preserveReadingFlags)
                outputs[prefs.path] = patch.prefs
                selectedSnapshot.game?.let { outputs[it.path] = requireNotNull(patch.game) }
                changes += patch.changes
            }
        }
        sections["角色"] = changes.toList()
        val itemStart = changes.size
        if (items.hasChanges) {
            val file = requireNotNull(item(selectedSnapshot, account)) { "没有与所选账号对应的物品存档" }
            val patch = ItemEngine.edit(file.bytes, items)
            outputs[file.path] = patch.bytes
            changes += patch.changes
        }
        sections["物品"] = changes.drop(itemStart)
        val mythicStart = changes.size
        if (mythic.hasChanges) {
            val file = requireNotNull(item(selectedSnapshot, account)) { "没有与所选账号对应的物品存档" }
            val patch = MythicEngine.edit(outputs[file.path] ?: file.bytes, mythic)
            outputs[file.path] = patch.bytes
            changes += patch.changes
        }
        sections["神话武器"] = changes.drop(mythicStart)
        if (items.hasChanges || mythic.hasChanges) {
            val file = requireNotNull(item(selectedSnapshot, account))
            val itemRoot = ItemCodec.decode(outputs[file.path] ?: file.bytes)
            // Apply increments once against the preferred shard. Existing companion encodings
            // receive the resulting amounts, rather than adding to their possibly stale counts.
            val companionSelection = items.copy(increments = emptyMap(), amounts = items.amounts +
                items.increments.keys.associateWith { ItemEngine.quantity(itemRoot, it) })
            fun patchCompanion(bytes: ByteArray): ByteArray {
                var result = bytes
                if (items.hasChanges) result = ItemEngine.edit(result, companionSelection).bytes
                if (mythic.hasChanges) result = MythicEngine.edit(result, mythic).bytes
                return result
            }
            selectedSnapshot.items.filter { itemAccount(it.path) == account && it.path != file.path }.forEach { companion ->
                val updated = patchCompanion(companion.bytes)
                if (!updated.contentEquals(companion.bytes)) {
                    outputs[companion.path] = updated
                    val sync = "同步同账号物品分片：${companion.path.substringAfterLast('/')}"
                    changes += sync
                    val section = if (mythic.hasChanges) "神话武器" else "物品"
                    sections[section] = sections[section].orEmpty() + sync
                }
            }
            selectedSnapshot.game?.let { game ->
                // itemData is optional. Parsing this object does not require legacy role/skin structures.
                val root = Json.parseToJsonElement(UnlockEngine.utf8(UnlockEngine.xor(outputs[game.path] ?: game.bytes))).jsonObject
                if ("itemData" in root) {
                    val originals = snapshot.items.filter { itemAccount(it.path) == account }.map { ItemCodec.decode(it.bytes) }
                    require(accounts(snapshot).size == 1 && root["itemData"] in originals) {
                        "game.data 的物品镜像与当前账号不一致，暂不写入"
                    }
                    val mirrorRoot = ItemCodec.decode(patchCompanion(ItemCodec.encode(root.getValue("itemData").jsonObject)))
                    if (root["itemData"] != mirrorRoot) {
                        val updated = JsonObject(root + ("itemData" to mirrorRoot))
                        outputs[game.path] = UnlockEngine.xor(updated.toString().toByteArray(Charsets.UTF_8))
                        val mirror = "同步已有的 game.data 物品镜像"
                        changes += mirror
                        val section = if (mythic.hasChanges) "神话武器" else "物品"
                        sections[section] = sections[section].orEmpty() + mirror
                    }
                }
            }
        }
        val weaponStart = changes.size
        if (weapons.hasChanges) {
            val file = requireNotNull(statistic(selectedSnapshot, account)) { "没有与所选账号对应的统计存档" }
            val item = requireNotNull(item(selectedSnapshot, account)) { "需要同账号的物品分片核对武器目录版本" }
            val patch = WeaponEngine.edit(file.bytes, item.bytes, weapons)
            outputs[file.path] = patch.bytes
            changes += patch.changes
            val resultingCounters = patch.root.getValue("object2ObtainTime").jsonObject
            val selected = if (weapons.allPlusEight) resultingCounters.keys.filter(WeaponCatalog::isWeaponId).toSet()
                else weapons.increments.filterValues { it > 0 }.keys
            selectedSnapshot.statistics.filter { statisticAccount(it.path) == account && it.path != file.path }.forEach { companion ->
                val root = WeaponEngine.inspect(companion.bytes, item.bytes)
                val counts = root.getValue("object2ObtainTime").jsonObject.toMutableMap()
                selected.forEach { id ->
                    val old = counts[id]
                    require(old == null || old is JsonPrimitive && !old.isString && old.intOrNull?.let { it >= 0 } == true) {
                        "$id 的伴随统计记录格式未知，暂不写入"
                    }
                    counts[id] = resultingCounters.getValue(id)
                }
                val updated = StatisticCodec.encode(JsonObject(root + ("object2ObtainTime" to JsonObject(counts))), companion.bytes)
                if (!updated.contentEquals(companion.bytes)) {
                    outputs[companion.path] = updated
                    changes += "同步同账号统计分片：${companion.path.substringAfterLast('/')}"
                }
            }
        }
        sections["武器"] = changes.drop(weaponStart)
        val skinStart = changes.size
        if (weaponSkins.hasChanges) {
            val file = requireNotNull(weaponSkinFile(selectedSnapshot, account)) { "没有与此账号对应的武器皮肤分片" }
            val item = requireNotNull(item(selectedSnapshot, account)) { "需要同账号的物品分片核对武器皮肤目录版本" }
            val patch = WeaponSkinEngine.edit(file.bytes, item.bytes, weaponSkins)
            outputs[file.path] = patch.bytes
            changes += patch.changes
            val definitions = WeaponSkinCatalog.forVersion(ItemEngine.inspect(item.bytes).getValue("AppVersion").jsonPrimitive.int)
            val requiredSkins = weaponSkins.current.mapNotNull { (weapon, index) ->
                if (index == 0) null else definitions.single { it.weapon == weapon && it.index == index }.id
            }
            val companionSelection = weaponSkins.copy(unlock = weaponSkins.unlock + requiredSkins)
            selectedSnapshot.weaponSkins.filter { weaponSkinAccount(it.path) == account && it.path != file.path }.forEach { companion ->
                // A current-only selection may already be owned in the modern shard but absent
                // from the stale legacy shard. Carry its verified unlock dependency as well.
                val updated = WeaponSkinEngine.edit(companion.bytes, item.bytes, companionSelection)
                outputs[companion.path] = updated.bytes
                if (!updated.bytes.contentEquals(companion.bytes)) changes += "同步同账号武器皮肤分片：${companion.path.substringAfterLast('/')}"
            }
        }
        sections["武器皮肤"] = changes.drop(skinStart)
        val evolutionStart = changes.size
        if (evolution.hasChanges) {
            val file = requireNotNull(weaponSkinFile(selectedSnapshot, account)) { "没有与此账号对应的武器进化分片" }
            val item = requireNotNull(item(selectedSnapshot, account)) { "需要同账号的物品分片核对武器进化目录版本" }
            val patch = WeaponEvolutionEngine.edit(outputs[file.path] ?: file.bytes, item.bytes, evolution)
            outputs[file.path] = patch.bytes
            changes += patch.changes
            selectedSnapshot.weaponSkins.filter { weaponSkinAccount(it.path) == account && it.path != file.path }.forEach { companion ->
                val updated = WeaponEvolutionEngine.edit(outputs[companion.path] ?: companion.bytes, item.bytes, evolution)
                outputs[companion.path] = updated.bytes
                if (!updated.bytes.contentEquals(companion.bytes)) changes += "同步同账号武器进化分片：${companion.path.substringAfterLast('/')}"
            }
        }
        sections["武器进化"] = changes.drop(evolutionStart)
        if (!preserveReadingFlags && (hasCharacters(characters) || items.hasChanges || weapons.hasChanges || weaponSkins.hasChanges || evolution.hasChanges || mythic.hasChanges)) selectedSnapshot.prefs?.let { prefs ->
            val current = outputs[prefs.path] ?: prefs.bytes
            val (bytes, flags) = UnlockEngine.localReadingFlags(current, account)
            if (!bytes.contentEquals(current)) outputs[prefs.path] = bytes
            changes += flags
            sections["读取来源"] = flags
        }
        return SavePlan(outputs.filter { (path, bytes) -> !selectedSnapshot.files.single { it.path == path }.bytes.contentEquals(bytes) }, changes, sections.filterValues { it.isNotEmpty() })
    }
}
