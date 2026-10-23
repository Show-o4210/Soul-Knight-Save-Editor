package com.example.soul_knight_save_editor.unlock

/** Stable source identities (also used by Shizuku IPC), with shared strict recognition syntax. */
enum class SaveSource(val filename: Regex) {
    GAME(Regex("^game\\.data$")),
    ITEM(Regex("^item_data(?:_(${SaveAccountId.TOKEN_PATTERN})_)?\\.data(?:\\.new)?$")),
    STATISTIC(Regex("^statistic(?:_(${SaveAccountId.TOKEN_PATTERN})_)?\\.data(?:\\.new)?$")),
    PREFS(Regex("^[a-zA-Z0-9_.-]+\\.xml$")),
    WEAPON_SKINS(Regex("^weapon_evolution_data(?:_(${SaveAccountId.TOKEN_PATTERN})_)?\\.data(?:\\.new)?$"));

    /** Canonical source namespace, also used when constructing synthetic/local snapshots. */
    val directory: String get() = if (this == PREFS) "shared_prefs" else "files"
}

object SaveLayout {
    // Backups also contain opaque .data shards. Only restore writes these; the editor still
    // selects sources through editablePath and the fixed source filename patterns below.
    // Native recovery candidates are retained verbatim by backup/restore, never editor sources.
    const val DATA_SUFFIX = "\\.data(?:\\.new)?(?:_temp)?"
    val backupDataPath = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+$DATA_SUFFIX$")
    val restorablePath = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/(?:files/(?:[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+$DATA_SUFFIX|shared_prefs/[a-zA-Z0-9_.-]+\\.xml)$")
    val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val dataNames = SaveSource.entries.filter { it != SaveSource.PREFS }
        .joinToString("|") { it.filename.pattern.removePrefix("^").removeSuffix("$") }
    val editablePath = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/(?:files(?:/[a-zA-Z0-9_.-]+)?/(?:$dataNames)|shared_prefs/[a-zA-Z0-9_.-]+\\.xml)$")
    fun account(path: String, source: SaveSource): String? {
        require(source in setOf(SaveSource.ITEM, SaveSource.STATISTIC, SaveSource.WEAPON_SKINS))
        return source.filename.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(1)
    }
    fun root(packageName: String, user: Int): String {
        require(packagePattern.matches(packageName) && user >= 0) { "包名或 Android 用户编号无效" }
        return "/data/user/$user/$packageName"
    }
}

/** Transport supplies bounded directory listings and verified reads; scanner owns recognition policy. */
interface SaveScanAccess {
    fun searchStrategy(packageName: String): ChannelSearchStrategy = ChannelSearchStrategies.forPackage(packageName)
    /** Legacy name: require no target processes; never force-stop the game. */
    fun stop(packageName: String, user: Int)
    fun exists(directory: String): Boolean
    fun list(root: String, source: SaveSource): List<String>
    fun read(path: String): SaveFile
}

/** Session-only location bookmark. Contains paths and identity, never cached save bytes. */
data class PinnedSaveTarget(val packageName: String, val user: Int, val account: String,
    val prefs: String?, val game: String?, val items: List<String>, val statistics: List<String>,
    val prefsHasAccount: Boolean, val weaponSkins: List<String> = emptyList()) {
    companion object {
        fun from(snapshot: SaveSnapshot, account: String): PinnedSaveTarget {
            require(account in EditEngine.accounts(snapshot))
            return PinnedSaveTarget(snapshot.packageName, snapshot.user, account, snapshot.prefs?.path, snapshot.game?.path,
                snapshot.items.map { it.path }, snapshot.statistics.map { it.path },
                snapshot.prefs?.let { account in UnlockEngine.accounts(it.bytes) } == true, snapshot.weaponSkins.map { it.path })
        }
    }
}

object SaveScanner {
    /** Re-read exactly the chosen locations. Missing or changed identity requires an explicit new scan. */
    fun refresh(access: SaveScanAccess, target: PinnedSaveTarget,
        strategy: ChannelSearchStrategy = access.searchStrategy(target.packageName)): SaveSnapshot {
        val root = SaveLayout.root(target.packageName, target.user)
        val paths = listOfNotNull(target.prefs, target.game) + target.items + target.statistics + target.weaponSkins
        require(paths.isNotEmpty() && paths.size <= 128 && paths.distinct().size == paths.size) { "存档位置记录无效，请重新查找" }
        fun validate(path: String, source: SaveSource) {
            val query = strategy.source(source)
            require(query.containsPath(root, path) && SaveLayout.editablePath.matches(path) &&
                source.filename.matches(path.substringAfterLast('/')) && query.matchesFilename(path.substringAfterLast('/'))) { "存档位置不属于当前版本，请重新查找" }
        }
        target.prefs?.let { validate(it, SaveSource.PREFS) }
        target.game?.let { validate(it, SaveSource.GAME) }
        target.items.forEach { validate(it, SaveSource.ITEM) }
        target.statistics.forEach { validate(it, SaveSource.STATISTIC) }
        target.weaponSkins.forEach { validate(it, SaveSource.WEAPON_SKINS) }
        access.stop(target.packageName, target.user)
        require(access.exists(root)) { "当前游戏版本已不存在，请重新查找" }
        // A game launch may migrate a legacy shard to .new after this bookmark was created.
        // Recheck bounded names for the selected account before trusting its saved locations.
        listOf(SaveSource.ITEM to target.items, SaveSource.STATISTIC to target.statistics,
            SaveSource.WEAPON_SKINS to target.weaponSkins).forEach { (source, pinned) ->
            val listed = access.list(root, source)
            require(listed.size <= 128 && listed.distinct().size == listed.size) { "当前候选文件过多或重复，请重新查找" }
            val query = strategy.source(source)
            val found = listed.filter { source.filename.matches(it.substringAfterLast('/')) }.onEach { path ->
                require(query.containsPath(root, path) && SaveLayout.editablePath.matches(path)) { "存档位置不属于当前版本，请重新查找" }
            }.filter { query.matchesFilename(it.substringAfterLast('/')) }
                .filter { SaveLayout.account(it, source) == target.account }.toSet()
            val expected = pinned.filter { SaveLayout.account(it, source) == target.account }.toSet()
            require(found == expected) { "所选账号的存档位置或新旧格式已变化，请重新查找后再编辑" }
        }
        val snapshot = SaveSnapshot(target.packageName, target.user, target.prefs?.let(access::read), target.game?.let(access::read),
            target.items.map(access::read), target.statistics.map(access::read), target.weaponSkins.map(access::read))
        SaveReadConsistency.verify(target.packageName, target.user, snapshot.files, access::stop, access::read)
        if (target.prefsHasAccount) require(snapshot.prefs?.let { target.account in UnlockEngine.accounts(it.bytes) } == true) { "存档账号已改变，请重新查找并选择账号" }
        require(target.account in EditEngine.accounts(snapshot)) { "存档账号已改变，请重新查找" }
        return snapshot
    }
    fun scan(access: SaveScanAccess, packageName: String, user: Int,
        strategy: ChannelSearchStrategy = access.searchStrategy(packageName)): SaveSnapshot {
        val root = SaveLayout.root(packageName, user)
        access.stop(packageName, user)
        require(access.exists(root)) { "未找到所选版本的数据，请切换游戏版本，或扫描全部游戏版本后再读取。" }
        fun files(source: SaveSource): List<String> {
            val query = strategy.source(source)
            val candidates = access.list(root, source)
            require(candidates.size <= 128) { "候选文件过多，暂不自动选择" }
            require(candidates.distinct().size == candidates.size) { "扫描列表含重复路径" }
            return candidates.filter { source.filename.matches(it.substringAfterLast('/')) }.onEach { path ->
                require(query.containsPath(root, path) && SaveLayout.editablePath.matches(path)) { "扫描路径超出目标应用范围" }
            }.filter { query.matchesFilename(it.substringAfterLast('/')) }
        }
        val gamePaths = files(SaveSource.GAME)
        require(gamePaths.size <= 1) { "找到多份 game.data，无法确定当前使用哪份，已保持只读" }
        val game = gamePaths.singleOrNull()?.let(access::read)
        val items = files(SaveSource.ITEM).map(access::read)
        val statistics = files(SaveSource.STATISTIC).map(access::read)
        val weaponSkins = files(SaveSource.WEAPON_SKINS).map(access::read)
        val prefs = files(SaveSource.PREFS).map(access::read).filter { UnlockEngine.recognizesPrefs(it.bytes) }
        require(prefs.size <= 1) { "找到多份包含角色特征的 XML，暂不自动选择" }
        require(prefs.isNotEmpty() || items.isNotEmpty() || statistics.isNotEmpty() || weaponSkins.isNotEmpty()) { "尚未识别到角色、物品、统计或武器皮肤存档，请检查渠道包名与本地存档" }
        return SaveSnapshot(packageName, user, prefs.singleOrNull(), game, items, statistics, weaponSkins).also {
            SaveReadConsistency.verify(packageName, user, it.files, access::stop, access::read)
        }
    }
}
