package com.example.soul_knight_save_editor.unlock

/** Register supported sources once; discovery, scanning and account binding share these names. */
enum class SaveSource(val directory: String, val depth: Int, val glob: String, val filename: Regex) {
    GAME("files", 2, "game.data", Regex("^game\\.data$")),
    ITEM("files", 2, "item_data*.data", Regex("^item_data(?:_(\\d+)_)?\\.data$")),
    STATISTIC("files", 2, "statistic*.data", Regex("^statistic(?:_(\\d+)_)?\\.data$")),
    PREFS("shared_prefs", 1, "*.xml", Regex("^[a-zA-Z0-9_.-]+\\.xml$"))
}

object SaveLayout {
    val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val dataNames = SaveSource.entries.filter { it.directory == "files" }
        .joinToString("|") { it.filename.pattern.removePrefix("^").removeSuffix("$") }
    val editablePath = Regex("^/data/(?:user/[0-9]+|data)/[a-zA-Z][a-zA-Z0-9_.]+/(?:files(?:/[a-zA-Z0-9_.-]+)?/(?:$dataNames)|shared_prefs/[a-zA-Z0-9_.-]+\\.xml)$")
    fun account(path: String, source: SaveSource): String? {
        require(source in setOf(SaveSource.ITEM, SaveSource.STATISTIC))
        return source.filename.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(1)
    }
    fun root(packageName: String, user: Int): String {
        require(packagePattern.matches(packageName) && user >= 0) { "包名或 Android 用户编号无效" }
        return "/data/user/$user/$packageName"
    }
}

/** Transport supplies bounded directory listings and verified reads; scanner owns recognition policy. */
interface SaveScanAccess {
    fun stop(packageName: String, user: Int)
    fun exists(directory: String): Boolean
    fun list(root: String, source: SaveSource): List<String>
    fun read(path: String): SaveFile
}

/** Session-only location bookmark. Contains paths and identity, never cached save bytes. */
data class PinnedSaveTarget(val packageName: String, val user: Int, val account: String,
    val prefs: String?, val game: String?, val items: List<String>, val statistics: List<String>,
    val prefsHasAccount: Boolean) {
    companion object {
        fun from(snapshot: SaveSnapshot, account: String): PinnedSaveTarget {
            require(account in EditEngine.accounts(snapshot))
            return PinnedSaveTarget(snapshot.packageName, snapshot.user, account, snapshot.prefs?.path, snapshot.game?.path,
                snapshot.items.map { it.path }, snapshot.statistics.map { it.path },
                snapshot.prefs?.let { account in UnlockEngine.accounts(it.bytes) } == true)
        }
    }
}

object SaveScanner {
    /** Re-read exactly the chosen locations. Missing or changed identity requires an explicit new scan. */
    fun refresh(access: SaveScanAccess, target: PinnedSaveTarget): SaveSnapshot {
        val root = SaveLayout.root(target.packageName, target.user)
        val paths = listOfNotNull(target.prefs, target.game) + target.items + target.statistics
        require(paths.isNotEmpty() && paths.size <= 128 && paths.distinct().size == paths.size) { "存档位置记录无效，请重新查找" }
        fun validate(path: String, source: SaveSource) {
            require(path.startsWith("$root/${source.directory}/") && !path.contains("..") && SaveLayout.editablePath.matches(path) &&
                source.filename.matches(path.substringAfterLast('/')) && path.removePrefix("$root/${source.directory}/").split('/').size <= source.depth) { "存档位置不属于当前版本，请重新查找" }
        }
        target.prefs?.let { validate(it, SaveSource.PREFS) }
        target.game?.let { validate(it, SaveSource.GAME) }
        target.items.forEach { validate(it, SaveSource.ITEM) }
        target.statistics.forEach { validate(it, SaveSource.STATISTIC) }
        access.stop(target.packageName, target.user)
        require(access.exists(root)) { "当前游戏版本已不存在，请重新查找" }
        val snapshot = SaveSnapshot(target.packageName, target.user, target.prefs?.let(access::read), target.game?.let(access::read),
            target.items.map(access::read), target.statistics.map(access::read))
        if (target.prefsHasAccount) require(snapshot.prefs?.let { target.account in UnlockEngine.accounts(it.bytes) } == true) { "存档账号已改变，请重新查找并选择账号" }
        require(target.account in EditEngine.accounts(snapshot)) { "存档账号已改变，请重新查找" }
        return snapshot
    }
    fun scan(access: SaveScanAccess, packageName: String, user: Int): SaveSnapshot {
        val root = SaveLayout.root(packageName, user)
        access.stop(packageName, user)
        require(access.exists(root)) { "未找到该渠道的应用数据，请确认包名" }
        fun files(source: SaveSource): List<String> {
            val candidates = access.list(root, source)
            require(candidates.size <= 128) { "候选文件过多，暂不自动选择" }
            require(candidates.distinct().size == candidates.size) { "扫描列表含重复路径" }
            return candidates.filter { source.filename.matches(it.substringAfterLast('/')) }.onEach { path ->
                val relative = path.removePrefix("$root/${source.directory}/")
                require(path.startsWith("$root/${source.directory}/") && !path.contains("..") &&
                    SaveLayout.editablePath.matches(path) && relative.split('/').size <= source.depth) { "扫描路径超出目标应用范围" }
            }
        }
        val gamePaths = files(SaveSource.GAME)
        require(gamePaths.size <= 1) { "找到多份 game.data，无法确定当前使用哪份，已保持只读" }
        val game = gamePaths.singleOrNull()?.let(access::read)
        val items = files(SaveSource.ITEM).map(access::read)
        val statistics = files(SaveSource.STATISTIC).map(access::read)
        val prefs = files(SaveSource.PREFS).map(access::read).filter { UnlockEngine.recognizesPrefs(it.bytes) }
        require(prefs.size <= 1) { "找到多份包含角色特征的 XML，暂不自动选择" }
        require(prefs.isNotEmpty() || items.isNotEmpty() || statistics.isNotEmpty()) { "尚未识别到角色、物品或统计存档，请检查渠道包名与本地存档" }
        return SaveSnapshot(packageName, user, prefs.singleOrNull(), game, items, statistics)
    }
}
