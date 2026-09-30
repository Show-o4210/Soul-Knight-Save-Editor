package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SaveScannerTest {
    private val root = "/data/user/0/com.test.game"
    private val xml = """<map><int name="42_c0_unlock" value="0"/></map>""".toByteArray()
    private class Access(val listings: Map<SaveSource, List<String>>, val bytes: Map<String, ByteArray>) : SaveScanAccess {
        var stopped: String? = null
        val reads = mutableListOf<String>()
        var listCalls = 0
        override fun stop(packageName: String, user: Int) { stopped = "$packageName/$user" }
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource): List<String> { listCalls++; return listings[source].orEmpty() }
        override fun read(path: String): SaveFile {
            reads += path
            return SaveFile(path, bytes.getValue(path), "1000:1000", "600", "")
        }
    }
    @Test fun scanRecognizesSourcesAndIgnoresNewFilesWithoutEditing() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val item = "$root/files/saves/item_data_42_.data"
        val stat = "$root/files/statistic_42_.data"
        val access = Access(mapOf(SaveSource.PREFS to listOf(prefs), SaveSource.ITEM to listOf(item, "$item.new"), SaveSource.STATISTIC to listOf(stat)), mapOf(prefs to xml, item to byteArrayOf(1), stat to byteArrayOf(2)))
        val snapshot = SaveScanner.scan(access, "com.test.game", 0)
        assertEquals("com.test.game/0", access.stopped)
        assertEquals(prefs, snapshot.prefs!!.path)
        assertEquals(item, snapshot.items.single().path)
        assertEquals(stat, snapshot.statistics.single().path)
        assertEquals(listOf("42"), EditEngine.accounts(snapshot))
        assertFalse("$item.new" in access.reads)
    }
    @Test fun duplicateGameOrPrefsRequireExplicitResolution() {
        val games = listOf("$root/files/game.data", "$root/files/sub/game.data")
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.scan(Access(mapOf(SaveSource.GAME to games), emptyMap()), "com.test.game", 0) }
        val prefs = listOf("$root/shared_prefs/a.xml", "$root/shared_prefs/b.xml")
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.scan(Access(mapOf(SaveSource.PREFS to prefs), prefs.associateWith { xml }), "com.test.game", 0) }
    }
    @Test fun scannerRejectsOutsidePackageTraversalAndExcessiveDepth() {
        for (path in listOf("/data/user/0/com.other.game/files/item_data_42_.data", "$root/files/a/b/item_data_42_.data", "$root/files/../item_data_42_.data")) {
            val access = Access(mapOf(SaveSource.ITEM to listOf(path)), emptyMap())
            assertThrows(IllegalArgumentException::class.java) { SaveScanner.scan(access, "com.test.game", 0) }
            assertTrue(access.reads.isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) { SaveLayout.root("com.test.game;echo", 0) }
    }
    @Test fun unreadableExistingGameIsNeverTreatedAsMissing() {
        val access = Access(mapOf(SaveSource.GAME to listOf("$root/files/game.data")), emptyMap())
        assertThrows(Exception::class.java) { SaveScanner.scan(access, "com.test.game", 0) }
    }
    @Test fun sourceRegistryUsesExactAccountNamesAndBoundsCandidateCounts() {
        assertEquals("", SaveLayout.account("item_data.data", SaveSource.ITEM))
        assertEquals("42", SaveLayout.account("statistic_42_.data", SaveSource.STATISTIC))
        assertNull(SaveLayout.account("statistic_42.data", SaveSource.STATISTIC))
        assertNull(SaveLayout.account("statistic_42_.data.new", SaveSource.STATISTIC))
        val access = Access(mapOf(SaveSource.ITEM to (0..128).map { "$root/files/item_data_${it}_.data" }), emptyMap())
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.scan(access, "com.test.game", 0) }
    }
    @Test fun pinnedRefreshReadsNewBytesAtTheSameLocationsWithoutSearching() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val item = "$root/files/item_data_42_.data"
        val bytes = mutableMapOf(prefs to xml, item to ItemCodec.encode(buildJsonObject { put("AppVersion", 80600); put("value", 1) }))
        val access = Access(mapOf(SaveSource.PREFS to listOf(prefs), SaveSource.ITEM to listOf(item)), bytes)
        val snapshot = SaveScanner.scan(access, "com.test.game", 0)
        val target = PinnedSaveTarget.from(snapshot, "42")
        val calls = access.listCalls
        bytes[item] = ItemCodec.encode(buildJsonObject { put("AppVersion", 80600); put("value", 2) })
        val refreshed = SaveScanner.refresh(access, target)
        assertEquals(calls, access.listCalls)
        assertEquals(snapshot.items.single().path, refreshed.items.single().path)
        assertEquals(1, ItemCodec.decode(snapshot.items.single().bytes)["value"]!!.jsonPrimitive.int)
        assertEquals(2, ItemCodec.decode(refreshed.items.single().bytes)["value"]!!.jsonPrimitive.int)
        assertEquals("42", target.account)
    }
    @Test fun missingPinnedFilesAndChangedAccountDoNotFallBackToOtherLocations() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val source = SaveSnapshot("com.test.game", 0, SaveFile(prefs, xml, "1000:1000", "600", ""), null)
        val target = PinnedSaveTarget.from(source, "42")
        val missing = Access(mapOf(SaveSource.PREFS to listOf("$root/shared_prefs/other.xml")), emptyMap())
        assertThrows(Exception::class.java) { SaveScanner.refresh(missing, target) }
        assertEquals(0, missing.listCalls)
        val changed = Access(emptyMap(), mapOf(prefs to String(xml).replace("42_", "99_").toByteArray()))
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.refresh(changed, target) }
        assertEquals(0, changed.listCalls)
    }
    @Test fun pinnedPathsAreValidatedBeforeClosingAnyGame() {
        val access = Access(emptyMap(), emptyMap())
        val target = PinnedSaveTarget("com.test.game", 0, "42", "/data/user/0/com.other.game/shared_prefs/prefs.xml", null, emptyList(), emptyList(), true)
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.refresh(access, target) }
        assertNull(access.stopped)
        assertTrue(access.reads.isEmpty())
    }
    @Test fun clearingStaleContentKeepsOnlyTheSessionBookmarkAndChangingPackageDropsIt() {
        val path = "$root/shared_prefs/prefs.xml"
        val snapshot = SaveSnapshot("com.test.game", 0, SaveFile(path, xml, "1000:1000", "600", ""), null)
        val target = PinnedSaveTarget.from(snapshot, "42")
        val state = AssistantState(snapshot = snapshot, pinnedTarget = target, choices = ModeChoices(quickRoles = true))
        val cleared = state.clearLoaded("refresh failed")
        assertEquals(target, cleared.pinnedTarget)
        assertNull(cleared.snapshot)
        assertEquals(ModeChoices(), cleared.choices)
        assertNull(state.selectPackage("com.other.game").pinnedTarget)
    }
}
