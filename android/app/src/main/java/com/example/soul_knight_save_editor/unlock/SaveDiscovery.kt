package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*

data class GameCandidate(val packageName: String, val label: String = packageName, val version: String = "未知版本", val evidence: List<String>)
data class DiscoveryProgress(val checked: Int = 0, val total: Int = 0, val skipped: Int = 0)
class DiscoveryCancelled : RuntimeException("已取消搜索")

/** Discovery recognizes a source; editing still validates the full snapshot independently. */
object SaveDiscovery {
    private val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val role = SaveAccountId.roleUnlock
    private val skin = SaveAccountId.skinUnlock
    const val MAX_PACKAGES = 512
    const val MAX_PROBES = 12
    fun packages(output: String, preferred: String, ownPackage: String): List<String> = output.lineSequence()
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
        .filter { packagePattern.matches(it) && it != ownPackage }.distinct()
        .sortedWith(compareBy<String> { when {
            it == preferred -> 0
            it.contains("ChillyRoom", true) || it.contains("DungeonShooter", true) || it.contains("soulknight", true) -> 1
            else -> 2
        } }.thenBy { it }).toList()

    fun xml(bytes: ByteArray): Boolean = runCatching {
        val nodes = UnlockEngine.prefs(bytes)
        val roles = nodes.keys.filter(role::matches)
        val skins = nodes.keys.filter(skin::matches)
        // One generic c0_unlock key is insufficient. Require a coherent account and multiple signals.
        (roles + skins).map { (role.matchEntire(it) ?: skin.matchEntire(it))!!.groupValues[1] }.distinct().any { account ->
            val r = roles.count { role.matchEntire(it)!!.groupValues[1] == account }
            val s = skins.count { skin.matchEntire(it)!!.groupValues[1] == account }
            val suffix = if (account.isEmpty()) "" else "_$account"
            val flags = listOf("OpenRijTest$suffix", "OpenNewtonJsonTest$suffix").any { it in nodes }
            r > 0 && s > 0 && (r + s >= 3 || flags)
        }
    }.getOrDefault(false)

    fun game(bytes: ByteArray): Boolean = runCatching { UnlockEngine.game(bytes); true }.getOrDefault(false)
    fun item(bytes: ByteArray): Boolean = runCatching {
        val root = ItemCodec.decode(bytes)
        val version = root["AppVersion"] as? JsonPrimitive
        version != null && !version.isString && version.intOrNull != null && version.int >= 0 &&
            listOf("materials", "seeds", "blueprints", "tokenTickets").all { root[it] is JsonObject }
    }.getOrDefault(false)
}
