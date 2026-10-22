package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WeaponSkinEngineTest {
    private val item = ItemCodec.encode(buildJsonObject { put("AppVersion", 80700) })
    private val original = Json.parseToJsonElement("""{
      "weapons":{"weapon_007":{"Name":"weapon_007","Level":7,"CurrentSkinIndex":0,
        "UnlockedSkins":["weapon_007_s_1","weapon_007_s_99"],"future":{"keep":true}},
        "weapon_future":{"opaque":[1,null]}},
      "favorWeapons":["weapon_007"],"lastOpenMachineHistoryStr0":"keep","blindBoxOpenCount0":9,"unknown":true
    }""").jsonObject
    private fun bytes(root: JsonObject = original) = ItemCodec.encode(root)
    private fun record(root: JsonObject, id: String = "weapon_007") = root.getValue("weapons").jsonObject.getValue(id).jsonObject
    private fun withRecord(extra: Map<String, JsonElement>) = JsonObject(original + ("weapons" to JsonObject(
        original.getValue("weapons").jsonObject + ("weapon_007" to JsonObject(record(original) + extra)))))

    @Test fun catalogUsesConfigIndexesAndRaritiesAndExcludesUnconfirmedIds() {
        assertEquals(184, WeaponSkinCatalog.entries.size)
        assertEquals(172, WeaponSkinCatalog.entries.map { it.weapon }.toSet().size)
        assertEquals(176, WeaponSkinCatalog.forVersion(80600).size)
        assertEquals(184, WeaponSkinCatalog.forVersion(80700).size)
        assertTrue(WeaponSkinCatalog.entries.all { it.id == "${it.weapon}_s_${it.index}" })
        assertEquals(184, WeaponSkinCatalog.entries.map { it.weapon to it.index }.toSet().size)
        val snow = WeaponSkinCatalog.entries.filter { it.weapon == "weapon_007" }
        assertEquals(listOf(1, 2), snow.map { it.index })
        assertEquals(listOf(3, 1), snow.map { it.rarity })
        for (id in listOf("weapon_405_s_1", "weapon_415_s_1", "weapon_358_s_1"))
            assertFalse(WeaponSkinCatalog.entries.any { it.id == id })
        assertFalse(WeaponSkinCatalog.forVersion(80600).any { it.id == "weapon_378_s_1" })
        assertThrows(IllegalArgumentException::class.java) { WeaponSkinCatalog.forVersion(80800) }
    }
    @Test fun unlockPreservesCurrentEvolutionUnknownFieldsAndOtherRecords() {
        val source = bytes()
        val patch = WeaponSkinEngine.edit(source, item, WeaponSkinSelection(unlock = setOf("weapon_007_s_2")))
        val changed = record(patch.root)
        assertEquals(record(original) - "UnlockedSkins", changed - "UnlockedSkins")
        assertEquals(JsonArray(listOf("weapon_007_s_1", "weapon_007_s_99", "weapon_007_s_2").map(::JsonPrimitive)), changed["UnlockedSkins"])
        assertEquals(original - "weapons", patch.root - "weapons")
        assertEquals(original.getValue("weapons").jsonObject - "weapon_007", patch.root.getValue("weapons").jsonObject - "weapon_007")
        assertEquals(original, ItemCodec.decode(source))
        val again = WeaponSkinEngine.edit(patch.bytes, item, WeaponSkinSelection(unlock = setOf("weapon_007_s_2")))
        assertArrayEquals(patch.bytes, again.bytes)
        assertTrue(again.changes.isEmpty())
    }
    @Test fun switchingRequiresUnlockAndIndexZeroIsAlwaysOriginal() {
        assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(bytes(), item,
            WeaponSkinSelection(current = mapOf("weapon_007" to 2))) }
        val patch = WeaponSkinEngine.edit(bytes(), item, WeaponSkinSelection(unlock = setOf("weapon_007_s_2"), current = mapOf("weapon_007" to 2)))
        assertEquals(JsonPrimitive(2), record(patch.root)["CurrentSkinIndex"])
        val reset = WeaponSkinEngine.edit(patch.bytes, item, WeaponSkinSelection(current = mapOf("weapon_007" to 0)))
        assertEquals(record(patch.root) - "CurrentSkinIndex", record(reset.root) - "CurrentSkinIndex")
        assertEquals(JsonPrimitive(0), record(reset.root)["CurrentSkinIndex"])
        assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(bytes(), item, WeaponSkinSelection(current = mapOf("weapon_007" to 99))) }
    }
    @Test fun missingKnownRecordStartsAtLevelZeroAndUnknownCurrentIsPreserved() {
        val patch = WeaponSkinEngine.edit(bytes(), item, WeaponSkinSelection(unlock = setOf("weapon_lighter_s_1")))
        val created = record(patch.root, "weapon_lighter")
        assertEquals(JsonPrimitive("weapon_lighter"), created["Name"])
        assertEquals(JsonPrimitive(0), created["Level"])
        assertEquals(JsonPrimitive(0), created["CurrentSkinIndex"])
        val unknown = withRecord(mapOf("CurrentSkinIndex" to JsonPrimitive(99)))
        val all = WeaponSkinEngine.edit(bytes(unknown), item, WeaponSkinSelection(unlockAll = true))
        assertEquals(JsonPrimitive(99), record(all.root)["CurrentSkinIndex"])
        assertEquals(JsonPrimitive(7), record(all.root)["Level"])
        assertTrue(JsonPrimitive("weapon_007_s_99") in record(all.root).getValue("UnlockedSkins").jsonArray)
        assertEquals(185, WeaponSkinEngine.inspect(all.bytes, item).states.sumOf { it.unlocked.size })
    }
    @Test fun highestQualityIsExplicitAndTiesKeepTheCurrentChoice() {
        val inventory = WeaponSkinEngine.inspect(bytes(), item)
        val snow = inventory.states.single { it.weapon == "weapon_007" }
        assertEquals(1, WeaponSkinEngine.highestAvailable(snow, WeaponSkinSelection(unlockAll = true)))
        assertEquals(0, snow.current)
        val basin = inventory.states.single { it.weapon == "weapon_223" }.copy(current = 3,
            unlocked = setOf("weapon_223_s_1", "weapon_223_s_2", "weapon_223_s_3"))
        assertEquals(3, WeaponSkinEngine.highestAvailable(basin, WeaponSkinSelection()))
        assertEquals(2, WeaponSkinEngine.highestAvailable(basin, WeaponSkinSelection(current = mapOf(basin.weapon to 2))))
        assertNull(WeaponSkinEngine.highestAvailable(snow.copy(unlocked = emptySet()), WeaponSkinSelection()))
    }
    @Test fun currentIndexUsesTheConfigIndexRatherThanTheUnlockedArrayPosition() {
        val source = withRecord(mapOf("CurrentSkinIndex" to JsonPrimitive(2), "UnlockedSkins" to
            JsonArray(listOf(JsonPrimitive("weapon_007_s_2"), JsonPrimitive("weapon_007_s_1")))))
        val patch = WeaponSkinEngine.edit(bytes(source), item, WeaponSkinSelection(current = mapOf("weapon_007" to 1)))
        assertEquals(JsonPrimitive(1), record(patch.root)["CurrentSkinIndex"])
        assertEquals(record(source) - "CurrentSkinIndex", record(patch.root) - "CurrentSkinIndex")
    }
    @Test fun wrongVersionUnconfirmedSkinsAndForeignWeaponIdsAreRejected() {
        for (version in listOf(JsonPrimitive(0), JsonPrimitive(80701), JsonPrimitive("80700"), JsonNull)) {
            assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.inspect(bytes(), ItemCodec.encode(buildJsonObject { put("AppVersion", version) })) }
        }
        for (id in listOf("weapon_405_s_1", "weapon_415_s_1", "weapon_007_s_99", "weapon_358_s_1"))
            assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(bytes(), item, WeaponSkinSelection(unlock = setOf(id))) }
        val legacy = ItemCodec.encode(buildJsonObject { put("AppVersion", 80600) })
        assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(bytes(), legacy, WeaponSkinSelection(unlock = setOf("weapon_378_s_1"))) }
        assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(bytes(), item, WeaponSkinSelection(current = mapOf("weapon_future" to 1))) }
    }
    @Test fun malformedSelectedRecordsRejectAtomicallyWithoutDisablingOtherWeapons() {
        val bad = listOf(mapOf("Name" to JsonPrimitive("weapon_006")), mapOf("Level" to JsonPrimitive(-1)),
            mapOf("CurrentSkinIndex" to JsonPrimitive("1")), mapOf("UnlockedSkins" to JsonNull),
            mapOf("UnlockedSkins" to JsonArray(listOf(JsonPrimitive("weapon_006_s_1")))))
        for (fields in bad) {
            val source = bytes(withRecord(fields))
            assertNotNull(WeaponSkinEngine.inspect(source, item).states.single { it.weapon == "weapon_007" }.error)
            assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.edit(source, item, WeaponSkinSelection(unlockAll = true)) }
            val other = WeaponSkinEngine.edit(source, item, WeaponSkinSelection(unlock = setOf("weapon_006_s_1")))
            assertEquals(record(ItemCodec.decode(source)), record(other.root))
        }
        for (root in listOf(JsonObject(emptyMap()), buildJsonObject { put("weapons", JsonArray(emptyList())) }))
            assertThrows(IllegalArgumentException::class.java) { WeaponSkinEngine.inspect(bytes(root), item) }
    }
    @Test fun privateCommunityReferenceConfirmsCatalogAndRetainsTwoUnconfirmedRecords() {
        val path = System.getProperty("weaponSkin.reference").orEmpty()
        assumeTrue("Optional community reference not supplied", path.isNotBlank())
        val file = File(path)
        val source = file.readBytes()
        assertEquals("3957b9f6177af6d535237346f90100450c1b953be2d5e2bcf0b5af8fb99c87b0", UnlockEngine.sha(source))
        val root = Json.parseToJsonElement(source.toString(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
        val encoded = bytes(root)
        val inventory = WeaponSkinEngine.inspect(encoded, item)
        assertTrue(inventory.states.all { it.error == null && it.skins.all { skin -> skin.id in it.unlocked } })
        assertEquals(2, inventory.unknownWeapons)
        assertTrue(WeaponSkinEngine.edit(encoded, item, WeaponSkinSelection(unlockAll = true)).changes.isEmpty())
        inventory.states.filter { it.skins.size > 1 }.forEach { weapon ->
            assertEquals(weapon.current, WeaponSkinEngine.highestAvailable(weapon, WeaponSkinSelection()))
        }
        val reset = WeaponSkinEngine.edit(encoded, item, WeaponSkinSelection(current = inventory.states.associate { it.weapon to 0 }))
        inventory.states.forEach { weapon -> assertEquals(record(root, weapon.weapon) - "CurrentSkinIndex", record(reset.root, weapon.weapon) - "CurrentSkinIndex") }
        for (id in listOf("weapon_405", "weapon_415")) assertEquals(record(root, id), record(reset.root, id))
        assertArrayEquals(source, file.readBytes())
    }
    @Test fun private87ShardUnlockAndSwitchLeaveEvolutionAndSourceFilesUnchanged() {
        val path = System.getProperty("save87.baseline").orEmpty()
        assumeTrue("Optional private 8.7 fixture not supplied", path.isNotBlank())
        val directory = File(path, "files")
        val skinFile = directory.listFiles()!!.single { it.name.startsWith("weapon_evolution_data_") && it.name.endsWith("_.data") }
        val itemFile = directory.listFiles()!!.single { it.name.startsWith("item_data_") && it.name.endsWith("_.data") }
        val source = skinFile.readBytes()
        val paired = itemFile.readBytes()
        val root = ItemCodec.decode(source)
        val inventory = WeaponSkinEngine.inspect(source, paired)
        assertTrue(inventory.states.all { it.error == null })
        val patch = WeaponSkinEngine.edit(source, paired, WeaponSkinSelection(unlockAll = true))
        root.getValue("weapons").jsonObject.forEach { (id, value) ->
            if (inventory.states.any { it.weapon == id }) assertEquals(value.jsonObject - "UnlockedSkins", record(patch.root, id) - "UnlockedSkins")
            else assertEquals(value, patch.root.getValue("weapons").jsonObject[id])
        }
        assertEquals(root - "weapons", patch.root - "weapons")
        assertTrue(WeaponSkinEngine.inspect(patch.bytes, paired).states.all { state -> state.skins.all { it.id in state.unlocked } })
        val switched = WeaponSkinEngine.edit(patch.bytes, paired, WeaponSkinSelection(current = mapOf("weapon_007" to 2)))
        assertEquals(record(patch.root) - "CurrentSkinIndex", record(switched.root) - "CurrentSkinIndex")
        assertEquals(JsonPrimitive(2), record(switched.root)["CurrentSkinIndex"])
        assertArrayEquals(source, skinFile.readBytes())
        assertArrayEquals(paired, itemFile.readBytes())
    }
}
