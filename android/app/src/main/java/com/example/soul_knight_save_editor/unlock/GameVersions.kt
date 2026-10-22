package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

/** Remember locations and display metadata only; every selection must read its own files again. */
object GameVersions {
    const val ORIGINAL = "com.ChillyRoom.DungeonShooter"
    private val original = GameCandidate(ORIGINAL, "元气骑士 · 原版", evidence = emptyList())

    fun add(games: List<GameCandidate>, candidate: GameCandidate): List<GameCandidate> {
        require(SaveLayout.packagePattern.matches(candidate.packageName)) { "游戏包名无效" }
        val existing = games.firstOrNull { it.packageName == candidate.packageName }
        val merged = candidate.copy(
            label = (candidate.label.takeUnless { it == candidate.packageName || it.isBlank() }
                ?: existing?.label ?: candidate.packageName).take(80),
            version = (candidate.version.takeUnless { it == "未知版本" || it.isBlank() }
                ?: existing?.version ?: "未知版本").take(80),
            evidence = (candidate.evidence + existing?.evidence.orEmpty()).distinct().take(6))
        val entries = linkedMapOf(ORIGINAL to (games.firstOrNull { it.packageName == ORIGINAL } ?: original))
        games.filter { SaveLayout.packagePattern.matches(it.packageName) }.forEach { entries[it.packageName] = it }
        entries[merged.packageName] = merged
        return entries.values.take(SaveDiscovery.MAX_PACKAGES)
    }

    fun restore(encoded: String?, legacyPackage: String? = null): List<GameCandidate> {
        var games = listOf(original)
        val entries = runCatching { Json.parseToJsonElement(encoded.orEmpty()) as? JsonArray }.getOrNull().orEmpty()
        entries.take(SaveDiscovery.MAX_PACKAGES).forEach { entry ->
            val candidate = runCatching {
                val obj = entry.jsonObject
                val pkg = obj.getValue("package").jsonPrimitive.content
                GameCandidate(pkg, obj["label"]?.jsonPrimitive?.content ?: pkg,
                    obj["version"]?.jsonPrimitive?.content ?: "未知版本", emptyList())
            }.getOrNull()
            if (candidate != null && SaveLayout.packagePattern.matches(candidate.packageName)) games = add(games, candidate)
        }
        if (legacyPackage != null && SaveLayout.packagePattern.matches(legacyPackage))
            games = add(games, GameCandidate(legacyPackage, evidence = emptyList()))
        return games
    }

    fun encode(games: List<GameCandidate>): String = JsonArray(games.map { game -> buildJsonObject {
        put("package", game.packageName); put("label", game.label); put("version", game.version)
    } }).toString()
}
