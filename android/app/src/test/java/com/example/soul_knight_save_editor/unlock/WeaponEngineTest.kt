package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WeaponEngineTest {
    private val item = ItemCodec.encode(buildJsonObject { put("AppVersion", 80600) })
    private val original = Json.parseToJsonElement("""{
        "object2ObtainTime":{"weapon_361":1,"weapon_future_blade":2,"material_cell":"preserve"},
        "StatisticsData":{"WeaponGameStatisticData":{"_weaponUsedTimes":{"weapon_361":5}}},
        "weapon2PurpleFramePassTime":{"weapon_361":3},"unknown":[null,{"keep":true}]
    }""").jsonObject
    private fun encoded(values: JsonElement) = StatisticCodec.encode(JsonObject(original + ("object2ObtainTime" to values)))

    @Test fun addsEightToExistingAndMissingWeaponsWhilePreservingOtherProgress() {
        val bytes = StatisticCodec.encode(original)
        val patch = WeaponEngine.addEight(bytes, item)
        val counts = patch.root.getValue("object2ObtainTime").jsonObject
        assertEquals(9, counts.getValue("weapon_361").jsonPrimitive.int)
        assertEquals(8, counts.getValue("weapon_359").jsonPrimitive.int)
        assertEquals(10, counts.getValue("weapon_future_blade").jsonPrimitive.int)
        assertEquals(JsonPrimitive("preserve"), counts["material_cell"])
        assertEquals(original - "object2ObtainTime", patch.root - "object2ObtainTime")
        assertEquals(original, StatisticCodec.decode(bytes))
        assertEquals(patch.root, StatisticCodec.decode(patch.bytes))
        assertTrue(patch.changes.any { "weapon_359" in it && "新增" in it })
        assertEquals(WeaponCatalog.names.size + 1, patch.changes.size)
        assertEquals(17, WeaponEngine.addEight(patch.bytes, item).root.getValue("object2ObtainTime").jsonObject.getValue("weapon_361").jsonPrimitive.int)
    }
    @Test fun specialNumberedWeaponsReceiveCountsWithoutChangingTheirOtherRequirements() {
        assertEquals("宁神", WeaponCatalog.names["weapon_361"])
        assertEquals("至尊权杖", WeaponCatalog.names["weapon_359"])
        assertFalse("weapon_899" in WeaponCatalog.names)
        val patch = WeaponEngine.addEight(StatisticCodec.encode(original), item)
        assertEquals(8, patch.root.getValue("object2ObtainTime").jsonObject.getValue("weapon_900").jsonPrimitive.int)
        assertEquals(original["StatisticsData"], patch.root["StatisticsData"])
    }
    @Test fun malformedCountersAndOverflowRejectTheWholeOperation() {
        for (value in listOf(JsonPrimitive(-1), JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(true), JsonNull, JsonObject(emptyMap()), JsonPrimitive(Int.MAX_VALUE - 7))) {
            assertThrows(IllegalArgumentException::class.java) { WeaponEngine.addEight(encoded(buildJsonObject { put("weapon_361", value) }), item) }
        }
        for (values in listOf(JsonNull, JsonArray(emptyList()), JsonPrimitive(1))) {
            assertThrows(IllegalArgumentException::class.java) { WeaponEngine.addEight(encoded(values), item) }
        }
        assertThrows(IllegalArgumentException::class.java) { WeaponEngine.addEight(StatisticCodec.encode(JsonObject(emptyMap())), item) }
        val max = WeaponEngine.addEight(encoded(buildJsonObject { put("weapon_361", Int.MAX_VALUE - 8) }), item)
        assertEquals(Int.MAX_VALUE, max.root.getValue("object2ObtainTime").jsonObject.getValue("weapon_361").jsonPrimitive.int)
    }
    @Test fun unknownGameVersionAndWrongCipherAreRejected() {
        val bytes = StatisticCodec.encode(original)
        for (version in listOf(JsonPrimitive(90000), JsonPrimitive("80600"), JsonNull)) {
            val wrong = ItemCodec.encode(buildJsonObject { put("AppVersion", version) })
            assertThrows(IllegalArgumentException::class.java) { WeaponEngine.inspect(bytes, wrong) }
        }
        assertThrows(IllegalArgumentException::class.java) { StatisticCodec.decode(ItemCodec.encode(original)) }
        assertThrows(IllegalArgumentException::class.java) { StatisticCodec.decode("bad data".toByteArray()) }
    }
    @Test fun optionalPairedPrivateSnapshotRoundTripsWithoutWritingTheFixture() {
        val statPath = System.getProperty("statistic.baseline", "").orEmpty()
        val itemPath = System.getProperty("item.baseline", "").orEmpty()
        assumeTrue(statPath.isNotBlank() && itemPath.isNotBlank())
        val bytes = File(statPath).readBytes()
        val source = StatisticCodec.decode(bytes)
        val patch = WeaponEngine.addEight(bytes, File(itemPath).readBytes())
        assertEquals(source - "object2ObtainTime", patch.root - "object2ObtainTime")
        source.getValue("object2ObtainTime").jsonObject.forEach { (id, value) ->
            val actual = patch.root.getValue("object2ObtainTime").jsonObject.getValue(id)
            if (WeaponCatalog.isWeaponId(id)) assertEquals(value.jsonPrimitive.int + 8, actual.jsonPrimitive.int)
            else assertEquals(value, actual)
        }
        assertEquals(patch.root, StatisticCodec.decode(patch.bytes))
        assertArrayEquals(bytes, File(statPath).readBytes())
    }
}
