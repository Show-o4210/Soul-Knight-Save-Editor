package com.example.soul_knight_save_editor.unlock

/** Official package search contract. Change this table without changing channel packages. */
object OriginalChannelSearch {
    val strategy = ChannelSearchStrategy("original", linkedMapOf(
        SaveSource.GAME to SaveSearchQuery("files", 2, listOf("game.data")),
        SaveSource.ITEM to SaveSearchQuery("files", 2, listOf("item_data*.data*")),
        SaveSource.STATISTIC to SaveSearchQuery("files", 2, listOf("statistic*.data*")),
        SaveSource.PREFS to SaveSearchQuery("shared_prefs", 1, listOf("*.xml")),
        SaveSource.WEAPON_SKINS to SaveSearchQuery("files", 2, listOf("weapon_evolution_data*.data*"))),
        discoveryPrefs = SaveSearchQuery("shared_prefs", 1, listOf("*.xml")),
        discoveryData = SaveSearchQuery("files", 2, listOf("game.data", "item_data*.data*")),
        backupData = SaveSearchQuery("files", 2, listOf("*.data", "*.data.new", "*.data_temp", "*.data.new_temp")))
}
