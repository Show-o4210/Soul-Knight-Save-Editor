package com.example.soul_knight_save_editor.unlock

/** The editor's target shard format; selecting it never changes the game's reading flags. */
enum class SaveDataMode(val label: String, val extension: String, val preserveReadingFlags: Boolean) {
    LEGACY("旧格式", ".data", false),
    MODERN("新格式", ".data.new", true);

    fun select(snapshot: SaveSnapshot): SaveSnapshot = snapshot.copy(
        items = snapshot.items.filter { it.path.endsWith(extension) },
        statistics = snapshot.statistics.filter { it.path.endsWith(extension) },
        weaponSkins = snapshot.weaponSkins.filter { it.path.endsWith(extension) }
    )

    companion object {
        fun detected(snapshot: SaveSnapshot, account: String): SaveDataMode? = snapshot.prefs?.let { prefs ->
            when (runCatching { ReadingFlagScope.readingMode(UnlockEngine.prefs(prefs.bytes), account) }.getOrNull()) {
                ReadingFlagScope.Mode.LEGACY -> LEGACY
                ReadingFlagScope.Mode.MODERN -> MODERN
                else -> null
            }
        }

        fun initial(snapshot: SaveSnapshot, account: String): SaveDataMode? {
            detected(snapshot, account)?.let { return it }
            val files = snapshot.items.filter { EditEngine.itemAccount(it.path) == account } +
                snapshot.statistics.filter { EditEngine.statisticAccount(it.path) == account } +
                snapshot.weaponSkins.filter { EditEngine.weaponSkinAccount(it.path) == account }
            val available = entries.filter { mode -> files.any { it.path.endsWith(mode.extension) } }
            return when (available.size) {
                0 -> LEGACY // XML-only accounts keep the established character editing route.
                1 -> available.single()
                else -> null
            }
        }
    }
}
