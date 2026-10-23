package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WeaponUpgradeEngineTest {
    private fun item(version: JsonElement = JsonPrimitive(80700), records: JsonElement = JsonArray(emptyList())) =
        ItemCodec.encode(buildJsonObject { put("AppVersion", version); put("mythicWeapons", records); put("keep", "opaque") })
    private fun evolution(record: JsonElement? = record(0), extra: Map<String, JsonElement> = emptyMap()) =
        ItemCodec.encode(buildJsonObject {
            put("weapons", JsonObject((if (record == null) emptyMap() else mapOf("weapon_007" to record)) + extra))
            put("lastOpenMachineHistoryStr0", "keep"); put("favorWeapons", JsonArray(listOf(JsonPrimitive("weapon_future"))))
        })
    private fun record(level: Int) = buildJsonObject {
        put("Name", "weapon_007"); put("Level", level); put("CurrentSkinIndex", 99)
        put("UnlockedSkins", JsonArray(listOf(JsonPrimitive("weapon_007_s_99"))))
        put("unknown", buildJsonObject { put("keep", true) })
    }
    private fun weapon(root: JsonObject, id: String = "weapon_007") = root.getValue("weapons").jsonObject.getValue(id).jsonObject
    private fun mythic(id: JsonElement, level: JsonElement) = buildJsonObject { put("id", id); put("level", level); put("unknown", "keep") }
    private fun mythics(root: JsonObject) = root.getValue("mythicWeapons").jsonArray

    @Test fun reviewedCatalogsBindVersionsAndOneEvolutionIsTheMaximum() {
        assertEquals(118, WeaponEvolutionCatalog.forVersion(80600).size)
        assertEquals(121, WeaponEvolutionCatalog.forVersion(80700).size)
        assertEquals(121, WeaponEvolutionCatalog.entries.map { it.id }.toSet().size)
        assertTrue(WeaponEvolutionCatalog.entries.all { it.maxLevel == 1 })
        assertEquals(setOf("weapon_018", "weapon_388", "weapon_378"), WeaponEvolutionCatalog.entries.filter { it.minVersion == 80700 }.map { it.id }.toSet())
        assertEquals((0..27).toList(), MythicCatalog.forVersion(80700).map { it.id })
        assertEquals(MythicCatalog.forVersion(80600), MythicCatalog.forVersion(80700))
        assertEquals("三尖两刃刀", MythicCatalog.entries.first().label)
        assertEquals("weapon_mythic_00", MythicCatalog.entries.first().weapon)
        assertTrue(MythicCatalog.entries.all { it.maxLevel == 3 })
    }

    @Test fun evolutionOnlyChangesLevelAndCanComposeWithSkinSelectionInEitherOrder() {
        val source = evolution()
        val root = ItemCodec.decode(source)
        val patch = WeaponEvolutionEngine.edit(source, item(), WeaponEvolutionSelection(levels = mapOf("weapon_007" to 1)))
        assertEquals(JsonPrimitive(1), weapon(patch.root)["Level"])
        assertEquals(weapon(root) - "Level", weapon(patch.root) - "Level")
        assertEquals(root - "weapons", patch.root - "weapons")
        val selection = WeaponSkinSelection(unlock = setOf("weapon_007_s_1"), current = mapOf("weapon_007" to 1))
        val after = WeaponSkinEngine.edit(patch.bytes, item(), selection)
        val skinsFirst = WeaponSkinEngine.edit(source, item(), selection)
        val before = WeaponEvolutionEngine.edit(skinsFirst.bytes, item(), WeaponEvolutionSelection(levels = mapOf("weapon_007" to 1)))
        assertEquals(after.root, before.root)
        assertEquals(JsonPrimitive(1), weapon(after.root)["CurrentSkinIndex"])
        assertTrue(JsonPrimitive("weapon_007_s_99") in weapon(after.root).getValue("UnlockedSkins").jsonArray)
        assertEquals(root, ItemCodec.decode(source))
    }

    @Test fun evolutionBatchCreatesOnlyConfirmedRecordsAndPreservesHigherAndUnknownValues() {
        val source = evolution(record(7), mapOf("weapon_future" to JsonPrimitive("opaque")))
        val patch = WeaponEvolutionEngine.edit(source, item(), WeaponEvolutionSelection(allMax = true))
        assertEquals(122, patch.root.getValue("weapons").jsonObject.size)
        assertEquals(JsonPrimitive(7), weapon(patch.root)["Level"])
        assertEquals(JsonPrimitive("opaque"), patch.root.getValue("weapons").jsonObject["weapon_future"])
        val added = weapon(patch.root, "weapon_378")
        assertEquals(JsonPrimitive("weapon_378"), added["Name"])
        assertEquals(JsonPrimitive(1), added["Level"])
        assertEquals(JsonPrimitive(0), added["CurrentSkinIndex"])
        assertEquals(JsonArray(emptyList()), added["UnlockedSkins"])
        val again = WeaponEvolutionEngine.edit(patch.bytes, item(), WeaponEvolutionSelection(allMax = true))
        assertArrayEquals(patch.bytes, again.bytes)
        assertTrue(again.changes.isEmpty())
        val noDowngrade = WeaponEvolutionEngine.edit(patch.bytes, item(), WeaponEvolutionSelection(levels = mapOf("weapon_007" to 0)))
        assertArrayEquals(patch.bytes, noDowngrade.bytes)
    }

    @Test fun malformedEvolutionTargetsRejectAtomicallyWhileOtherRecordsCanBeEdited() {
        val malformed = listOf(JsonPrimitive("opaque"), JsonObject(record(0) + ("Name" to JsonPrimitive("weapon_006"))),
            JsonObject(record(0) + ("Level" to JsonPrimitive("0"))), JsonObject(record(0) + ("CurrentSkinIndex" to JsonPrimitive(-1))),
            JsonObject(record(0) + ("UnlockedSkins" to JsonArray(listOf(JsonPrimitive("weapon_006_s_1"))))))
        malformed.forEach { bad ->
            val source = evolution(bad)
            assertNotNull(WeaponEvolutionEngine.inspect(source, item()).states.single { it.definition.id == "weapon_007" }.error)
            assertThrows(IllegalArgumentException::class.java) { WeaponEvolutionEngine.edit(source, item(), WeaponEvolutionSelection(allMax = true)) }
            val other = WeaponEvolutionEngine.edit(source, item(), WeaponEvolutionSelection(levels = mapOf("weapon_006" to 1)))
            assertEquals(bad, other.root.getValue("weapons").jsonObject["weapon_007"])
        }
    }

    @Test fun evolutionRejectsUnsupportedVersionsWeaponsAndLevels() {
        val source = evolution()
        listOf(JsonPrimitive(0), JsonPrimitive(80701), JsonPrimitive("80700"), JsonNull).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { WeaponEvolutionEngine.inspect(source, item(version)) }
            assertThrows(IllegalArgumentException::class.java) { MythicEngine.inspect(item(version)) }
        }
        for ((id, level) in listOf("weapon_007" to -1, "weapon_007" to 2, "weapon_mythic_00" to 1, "weapon_future" to 1))
            assertThrows(IllegalArgumentException::class.java) { WeaponEvolutionEngine.edit(source, item(), WeaponEvolutionSelection(levels = mapOf(id to level))) }
        assertThrows(IllegalArgumentException::class.java) { WeaponEvolutionEngine.edit(source, item(JsonPrimitive(80600)),
            WeaponEvolutionSelection(levels = mapOf("weapon_378" to 1))) }
    }

    @Test fun mythicUnlockUsesNumericIdsAndLevelZeroWithoutChangingAnOwnedLevel() {
        val records = JsonArray(listOf(mythic(JsonPrimitive(0), JsonPrimitive(-1)), mythic(JsonPrimitive(1), JsonPrimitive(2)),
            mythic(JsonPrimitive(99), JsonPrimitive("opaque"))))
        val source = item(records = records)
        val patch = MythicEngine.edit(source, MythicSelection(unlock = setOf(0, 1, 2)))
        val result = mythics(patch.root)
        assertEquals(4, result.size)
        assertEquals(JsonPrimitive(0), result[0].jsonObject["level"])
        assertEquals(JsonPrimitive(2), result[1].jsonObject["level"])
        assertEquals(records[2], result[2])
        assertEquals(JsonPrimitive(2), result[3].jsonObject["id"])
        assertEquals(JsonPrimitive(0), result[3].jsonObject["level"])
        assertEquals(records[0].jsonObject - "level", result[0].jsonObject - "level")
        assertEquals(ItemCodec.decode(source) - "mythicWeapons", patch.root - "mythicWeapons")
        assertEquals(records, mythics(ItemCodec.decode(source)))
    }

    @Test fun mythicMaxCreatesKnownMissingIdsAndPreservesHigherRecordsAndOrder() {
        val source = item(records = JsonArray(listOf(mythic(JsonPrimitive(10), JsonPrimitive(7)), mythic(JsonPrimitive(99), JsonPrimitive(2)))))
        val patch = MythicEngine.edit(source, MythicSelection(maxAll = true))
        val records = mythics(patch.root)
        assertEquals(29, records.size)
        assertEquals(JsonPrimitive(10), records[0].jsonObject["id"])
        assertEquals(JsonPrimitive(7), records[0].jsonObject["level"])
        assertEquals(JsonPrimitive(99), records[1].jsonObject["id"])
        assertEquals(JsonPrimitive(2), records[1].jsonObject["level"])
        assertEquals(27, records.drop(2).count { it.jsonObject["level"] == JsonPrimitive(3) })
        val again = MythicEngine.edit(patch.bytes, MythicSelection(maxAll = true))
        assertArrayEquals(patch.bytes, again.bytes)
        assertTrue(again.changes.isEmpty())
        val lower = MythicEngine.edit(patch.bytes, MythicSelection(levels = mapOf(10 to 1)))
        assertArrayEquals(patch.bytes, lower.bytes)
    }

    @Test fun individualMythicTargetIncludesUnlockAndAllUnlockDoesNotUpgradeOwnedWeapons() {
        val patch = MythicEngine.edit(item(), MythicSelection(levels = mapOf(27 to 2)))
        assertEquals(JsonPrimitive(27), mythics(patch.root).single().jsonObject["id"])
        assertEquals(JsonPrimitive(2), mythics(patch.root).single().jsonObject["level"])
        val all = MythicEngine.edit(patch.bytes, MythicSelection(unlockAll = true))
        assertEquals(28, mythics(all.root).size)
        assertEquals(JsonPrimitive(2), mythics(all.root)[0].jsonObject["level"])
        assertTrue(mythics(all.root).drop(1).all { it.jsonObject["level"] == JsonPrimitive(0) })
    }

    @Test fun fullUpgradeActionsTakePrecedenceOverLowerExplicitTargets() {
        val ordinary = WeaponEvolutionEngine.edit(evolution(), item(), WeaponEvolutionSelection(allMax = true,
            levels = mapOf("weapon_007" to 0)))
        assertEquals(JsonPrimitive(1), weapon(ordinary.root)["Level"])
        val mythical = MythicEngine.edit(item(), MythicSelection(unlockAll = true, maxAll = true, levels = mapOf(0 to 0)))
        assertTrue(MythicEngine.inspect(mythical.bytes).states.all { it.level == 3 })
    }

    @Test fun mythicRejectsUnknownIdsInvalidLevelsAndAmbiguousLists() {
        for (selection in listOf(MythicSelection(unlock = setOf(-1)), MythicSelection(unlock = setOf(28)),
            MythicSelection(levels = mapOf(0 to -1)), MythicSelection(levels = mapOf(0 to 4))))
            assertThrows(IllegalArgumentException::class.java) { MythicEngine.edit(item(), selection) }
        for (records in listOf(JsonNull, JsonPrimitive("opaque"), JsonArray(listOf(JsonNull)),
            JsonArray(listOf(mythic(JsonPrimitive("0"), JsonPrimitive(0))))))
            assertThrows(IllegalArgumentException::class.java) { MythicEngine.inspect(item(records = records)) }
        for (records in listOf(JsonArray(listOf(mythic(JsonPrimitive(0), JsonPrimitive(-2)))),
            JsonArray(listOf(mythic(JsonPrimitive(0), JsonPrimitive("0")))),
            JsonArray(listOf(mythic(JsonPrimitive(0), JsonPrimitive(0)), mythic(JsonPrimitive(0), JsonPrimitive(1)))))) {
            val source = item(records = records)
            assertNotNull(MythicEngine.inspect(source).states.first().error)
            assertThrows(IllegalArgumentException::class.java) { MythicEngine.edit(source, MythicSelection(maxAll = true)) }
            val other = MythicEngine.edit(source, MythicSelection(unlock = setOf(1)))
            assertEquals(records.toList(), mythics(other.root).take(records.size))
        }
    }

    @Test fun actual87SamplesCanPreviewBothUpgradeFamiliesWithoutChangingTheirInputs() {
        val directory = System.getProperty("save87.baseline")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        val files = directory!!.walkTopDown().filter { it.isFile && it.parentFile?.name == "files" }.toList()
        val itemFile = files.single { it.name.matches(Regex("item_data_\\d+_\\.data")) }
        val evolutionFile = files.single { it.name.matches(Regex("weapon_evolution_data_\\d+_\\.data")) }
        val itemBytes = itemFile.readBytes()
        val evolutionBytes = evolutionFile.readBytes()
        val upgrade = WeaponEvolutionEngine.edit(evolutionBytes, itemBytes, WeaponEvolutionSelection(allMax = true))
        assertTrue(WeaponEvolutionEngine.inspect(upgrade.bytes, itemBytes).states.all { it.level!! >= 1 })
        val mythic = MythicEngine.edit(itemBytes, MythicSelection(maxAll = true))
        assertTrue(MythicEngine.inspect(mythic.bytes).states.all { it.level!! >= 3 })
        assertArrayEquals(itemBytes, itemFile.readBytes())
        assertArrayEquals(evolutionBytes, evolutionFile.readBytes())
    }
}
