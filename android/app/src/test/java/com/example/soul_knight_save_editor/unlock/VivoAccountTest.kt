package com.example.soul_knight_save_editor.unlock

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class VivoAccountTest {
    // Synthetic IDs; private channel samples are injected separately, never committed.
    private val account = "0123456789abcdef"
    private val other = "fedcba9876543210"
    private val pkg = "com.liangwu.yuanqiqishi.vivo"
    private val root = "/data/user/0/$pkg"
    private val cell = ItemKey("materials", "material_cell")
    private fun xml(id: String) = """<string name="${id}_c0_unlock">false</string><int name="${id}_c0_skin0" value="0"/><int name="${id}_c0_skin1" value="0"/><int name="OpenRijTest_$id" value="1"/>"""
    private fun item(version: Int = 80600) = ItemCodec.encode(buildJsonObject {
        put("AppVersion", version); put("materials", buildJsonObject { put("material_cell", 3) })
        put("seeds", buildJsonObject {}); put("blueprints", buildJsonObject {}); put("tokenTickets", buildJsonObject {})
        put("keep", "unchanged")
    })
    private fun statistic() = StatisticCodec.encode(buildJsonObject {
        put("object2ObtainTime", buildJsonObject { put("weapon_359", 1) }); put("keep", "unchanged")
    })
    private class Access(val files: Map<String, ByteArray>) : SaveScanAccess {
        var searches = 0
        override fun stop(packageName: String, user: Int) = Unit
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource): List<String> {
            searches++
            // Transport returns glob candidates; production scanner owns the strict filename filter.
            return files.keys.filter { path -> when (source) {
                SaveSource.PREFS -> "/shared_prefs/" in path
                SaveSource.GAME -> path.endsWith("/game.data")
                SaveSource.ITEM -> path.substringAfterLast('/').startsWith("item_data")
                SaveSource.STATISTIC -> path.substringAfterLast('/').startsWith("statistic")
            } }
        }
        override fun read(path: String) = SaveFile(path, files.getValue(path), "1000:1000", "600", "")
    }
    @Test fun exactConfirmedAccountFormatsAreSharedByXmlAndFilenames() {
        for (id in listOf("42", "00042", account)) {
            assertEquals(id, SaveAccountId.roleUnlock.matchEntire("${id}_c0_unlock")!!.groupValues[1])
            assertEquals(id, SaveLayout.account("item_data_${id}_.data", SaveSource.ITEM))
            assertEquals(id, SaveLayout.account("statistic_${id}_.data", SaveSource.STATISTIC))
        }
        assertEquals("", SaveLayout.account("item_data.data", SaveSource.ITEM))
        assertTrue(SaveAccountId.roleUnlock.matches("c0_unlock"))
        for (id in listOf("abcd", "0123456789abcdeg", "0123456789abcdef0", "uid_name", "../42", "42;echo", "0123456789ABCDEF")) {
            assertFalse(SaveAccountId.roleUnlock.matches("${id}_c0_unlock"))
            assertNull(SaveLayout.account("item_data_${id}_.data", SaveSource.ITEM))
        }
        assertNull(SaveLayout.account("item_data_${account}_.data.new", SaveSource.ITEM))
    }
    @Test fun discoveryScanAndPinnedRefreshBindTheChannelAccountWithoutDefaultFallback() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val shard = "$root/files/item_data_${account}_.data"
        val values = mutableMapOf(prefs to "<map>${xml(account)}</map>".toByteArray(),
            shard to item(), "$root/files/item_data.data" to item(0),
            "$root/files/statistic_${account}_.data" to statistic(), "$shard.new" to byteArrayOf(1))
        assertTrue(SaveDiscovery.xml(values.getValue(prefs)))
        val access = Access(values)
        val snapshot = SaveScanner.scan(access, pkg, 0)
        assertEquals(listOf(account), EditEngine.accounts(snapshot))
        assertEquals(2, snapshot.items.size)
        assertEquals(shard, EditEngine.item(snapshot, account)!!.path)
        assertNotNull(snapshot.prefs)
        val pinned = PinnedSaveTarget.from(snapshot, account)
        val searches = access.searches
        values[shard] = ItemEngine.edit(values.getValue(shard), ItemSelection(increments = mapOf(cell to 2))).bytes
        assertEquals(5, ItemEngine.quantity(ItemCodec.decode(EditEngine.item(SaveScanner.refresh(access, pinned), account)!!.bytes), cell))
        assertEquals(searches, access.searches)
        values[prefs] = "<map>${xml(other)}</map>".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { SaveScanner.refresh(access, pinned) }
    }
    @Test fun combinedEditTouchesOnlySelectedChannelAccountAndPreservesOtherAndDefaultFiles() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val values = linkedMapOf(prefs to "<map>${xml(account)}${xml(other)}${xml("42")}</map>".toByteArray(),
            "$root/files/item_data.data" to item(0), "$root/files/statistic.data" to statistic())
        for (id in listOf(account, other, "42")) {
            values["$root/files/item_data_${id}_.data"] = item()
            values["$root/files/statistic_${id}_.data"] = statistic()
        }
        val before = values.mapValues { it.value.copyOf() }
        val snapshot = SaveScanner.scan(Access(values), pkg, 0)
        val plan = EditEngine.preview(snapshot, account, UnlockSelection(heroes = setOf(0)),
            ItemSelection(increments = mapOf(cell to 1000)), WeaponSelection(increments = mapOf("weapon_359" to 8)))
        assertEquals(setOf(prefs, "$root/files/item_data_${account}_.data", "$root/files/statistic_${account}_.data"), plan.outputs.keys)
        assertEquals(1003, ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue("$root/files/item_data_${account}_.data")), cell))
        assertEquals(9, StatisticCodec.decode(plan.outputs.getValue("$root/files/statistic_${account}_.data"))["object2ObtainTime"]!!.jsonObject.getValue("weapon_359").jsonPrimitive.int)
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs))
        assertEquals("true", nodes.getValue("${account}_c0_unlock").textContent)
        for (id in listOf(other, "42")) {
            assertEquals("false", nodes.getValue("${id}_c0_unlock").textContent)
            assertEquals("1", nodes.getValue("OpenRijTest_$id").getAttribute("value"))
        }
        values.forEach { (path, bytes) -> assertArrayEquals(before.getValue(path), bytes) }
    }
    @Test fun wrongVersionDuplicateShardsAndAmbiguousGameRemainBlocked() {
        val prefs = "$root/shared_prefs/playerprefs.xml"
        val values = mutableMapOf(prefs to "<map>${xml(account)}</map>".toByteArray(), "$root/files/item_data_${account}_.data" to item(0))
        val snapshot = SaveScanner.scan(Access(values), pkg, 0)
        assertThrows(IllegalArgumentException::class.java) { ItemEngine.inspect(EditEngine.item(snapshot, account)!!.bytes) }
        values["$root/files/sub/item_data_${account}_.data"] = item()
        assertThrows(IllegalArgumentException::class.java) { EditEngine.item(SaveScanner.scan(Access(values), pkg, 0), account) }
        val multi = "<map>${xml(account)}${xml(other)}</map>".toByteArray()
        val game = UnlockEngine.xor("""{"heroUnlock":{"Knight":false},"skinLock":{"Knight":[]}}""".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { UnlockEngine.catalog(game, multi, account) }
    }
    @Test fun optionalPrivateVivoSampleInspectsAndCalculatesCombinedChangesWithoutWriting() {
        val directory = System.getProperty("vivo.baseline", "").orEmpty()
        assumeTrue("Optional private vivo samples not supplied", directory.isNotBlank())
        val folder = File(directory)
        val source = folder.listFiles()!!.filter { file -> file.name == "game.data" ||
            SaveSource.ITEM.filename.matches(file.name) || SaveSource.STATISTIC.filename.matches(file.name) || file.name.endsWith(".playerprefs.xml") }
        val before = source.associate { it.name to it.readBytes() }
        val values = before.mapKeys { (name, _) -> "$root/${if (name.endsWith(".xml")) "shared_prefs" else "files"}/$name" }
        val snapshot = SaveScanner.scan(Access(values), pkg, 0)
        assertNotNull(snapshot.prefs)
        val id = EditEngine.accounts(snapshot).single()
        assertTrue(Regex("[0-9a-f]{16}").matches(id))
        val report = SaveInspector.inspect(snapshot, id)
        val catalog = report.characters.getOrThrow()
        val progress = report.progression.getOrThrow()
        val pets = report.pets.getOrThrow()
        report.items.getOrThrow(); report.weapons.getOrThrow()
        assertTrue(catalog.heroes.isNotEmpty()); assertTrue(pets.isNotEmpty()); assertTrue(progress.isNotEmpty())
        val hero = catalog.heroes.first { it.unlocked != null }
        val plan = EditEngine.preview(snapshot, id, UnlockSelection(heroes = setOf(hero.index), pets = true),
            ItemSelection(increments = mapOf(cell to 1000)), WeaponSelection(increments = mapOf("weapon_359" to 8)))
        assertTrue(plan.outputs.isNotEmpty())
        assertTrue(plan.outputs.keys.none { it.endsWith("/item_data.data") || it.endsWith("/statistic.data") || it.endsWith(".new") })
        val updated = snapshot.copy(
            prefs = snapshot.prefs!!.let { it.copy(bytes = plan.outputs[it.path] ?: it.bytes) },
            game = snapshot.game?.let { it.copy(bytes = plan.outputs[it.path] ?: it.bytes) },
            items = snapshot.items.map { it.copy(bytes = plan.outputs[it.path] ?: it.bytes) },
            statistics = snapshot.statistics.map { it.copy(bytes = plan.outputs[it.path] ?: it.bytes) })
        val verified = SaveInspector.inspect(updated, id)
        verified.characters.getOrThrow(); verified.pets.getOrThrow(); verified.items.getOrThrow(); verified.weapons.getOrThrow()
        assertEquals(ItemEngine.quantity(report.items.getOrThrow(), cell) + 1000,
            ItemEngine.quantity(verified.items.getOrThrow(), cell))
        val oldCount = StatisticCodec.decode(EditEngine.statistic(snapshot, id)!!.bytes)["object2ObtainTime"]!!.jsonObject["weapon_359"]?.jsonPrimitive?.int ?: 0
        val newCount = StatisticCodec.decode(EditEngine.statistic(updated, id)!!.bytes)["object2ObtainTime"]!!.jsonObject.getValue("weapon_359").jsonPrimitive.int
        assertEquals(oldCount + 8, newCount)
        source.forEach { assertArrayEquals(before.getValue(it.name), it.readBytes()) }
    }
}
