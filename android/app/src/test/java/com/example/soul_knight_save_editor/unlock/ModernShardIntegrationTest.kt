package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModernShardIntegrationTest {
    private val iron = ItemKey("materials", "material_iron")
    private fun root(amount: Int, extra: String = "new") = buildJsonObject {
        put("AppVersion", 80700)
        put("materials", buildJsonObject { put("material_iron", amount); put("material_wood", if (extra == "old") 3000 else 3) })
        put("untouched", extra)
        put("eventRecords", if (extra == "old") buildJsonObject { put("keep", 9) } else JsonArray(emptyList()))
    }
    private fun file(name: String, bytes: ByteArray) = SaveFile("/data/user/0/com.test.game/files/$name", bytes, "10001:10001", "600", "")
    private fun prefs(flag: String? = "1"): SaveFile {
        val xml = """<map><int name="42_c0_unlock" value="0"/>
            <string name="AccountSaveSettingsIds">{"42":"314"}</string>
            ${if (flag == null) "" else "<int name=\"OpenRijTest_314\" value=\"$flag\"/>"}
            <int name="OpenNewtonJsonTest_314" value="${flag ?: "1"}"/>
            <int name="OpenRijTest_99" value="1"/></map>""".trimIndent().toByteArray()
        return file("prefs.xml", xml).copy(path = "/data/user/0/com.test.game/shared_prefs/prefs.xml")
    }
    private fun pair(oldAmount: Int = 3000, newAmount: Int = 7, flag: String? = "1"): SaveSnapshot = SaveSnapshot("com.test.game", 0, prefs(flag), null,
        listOf(file("item_data_42_.data", ItemCodec.encode(root(oldAmount, "old"))),
            file("item_data_42_.data.new", RijndaelCodec.encode(root(newAmount)))))

    @Test fun modernBaselineIncrementRunsOnceAndPreservesEachEncodingAndUnknownFields() {
        val snapshot = pair()
        assertTrue(EditEngine.item(snapshot, "42")!!.path.endsWith(".new"))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(increments = mapOf(iron to 1000)))
        assertEquals(snapshot.items.map { it.path }.toSet(), plan.outputs.keys)
        snapshot.items.forEach { source ->
            val result = plan.outputs.getValue(source.path)
            val decoded = ItemCodec.decode(result)
            assertEquals(1007, ItemEngine.quantity(decoded, iron))
            assertEquals(ItemCodec.format(source.bytes), ItemCodec.format(result))
            val original = ItemCodec.decode(source.bytes)
            assertEquals(original["untouched"], decoded["untouched"])
            assertEquals(original["eventRecords"], decoded["eventRecords"])
            assertEquals(original["materials"]!!.jsonObject["material_wood"], decoded["materials"]!!.jsonObject["material_wood"])
            assertEquals(if (source.path.endsWith(".new")) 3 else 3000,
                ItemEngine.quantity(decoded, ItemKey("materials", "material_wood")))
        }
        val prefs = requireNotNull(snapshot.prefs)
        assertArrayEquals(prefs.bytes, plan.outputs[prefs.path] ?: prefs.bytes)
        val nodes = UnlockEngine.prefs(plan.outputs[prefs.path] ?: prefs.bytes)
        assertEquals("1", nodes.getValue("OpenRijTest_314").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenNewtonJsonTest_314").getAttribute("value"))
    }

    @Test fun explicitUnchangedModernAmountStillRepairsTheLegacyCompanion() {
        val snapshot = pair()
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(amounts = mapOf(iron to 7)))
        assertEquals(setOf(snapshot.items.first().path), plan.outputs.keys)
        assertEquals(7, ItemEngine.quantity(ItemCodec.decode(plan.outputs.values.single()), iron))
    }

    @Test fun modernOnlyShardIsEditableAndOtherAccountIsUntouched() {
        val source = pair().items.last()
        val other = source.copy(path = source.path.replace("42_", "99_"))
        val snapshot = pair().copy(items = listOf(source, other))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(amounts = mapOf(iron to 123)))
        assertEquals(setOf(source.path), plan.outputs.keys)
        assertEquals(123, ItemEngine.quantity(RijndaelCodec.decode(plan.outputs.getValue(source.path)), iron))
    }

    @Test fun duplicatePhysicalLocationsRemainAmbiguousEvenWithANewFormat() {
        val snapshot = pair()
        val duplicate = snapshot.items.last().copy(path = snapshot.items.last().path.replace("/files/", "/files/sub/"))
        assertThrows(IllegalArgumentException::class.java) {
            EditEngine.preview(snapshot.copy(items = listOf(snapshot.items.first(), duplicate)), "42", UnlockSelection(), ItemSelection(amounts = mapOf(iron to 1)))
        }
    }

    @Test fun corruptModernShardDoesNotSilentlyFallBackToStaleLegacyData() {
        val snapshot = pair().let { it.copy(items = listOf(it.items.first(), it.items.last().copy(bytes = ByteArray(32) { 1 }))) }
        assertThrows(IllegalArgumentException::class.java) {
            EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(amounts = mapOf(iron to 1)))
        }
    }

    @Test fun weaponIncrementUsesModernCounterAndCopiesOnlySelectedCounter() {
        fun stat(count: Int, other: Int, modern: Boolean): SaveFile {
            val root = buildJsonObject {
                put("object2ObtainTime", buildJsonObject { put("weapon_361", count); put("weapon_362", other) })
                put("nativeType", if (modern) JsonPrimitive(4) else JsonPrimitive("4"))
            }
            return file("statistic_42_.data" + if (modern) ".new" else "", if (modern) RijndaelCodec.encode(root) else StatisticCodec.encode(root))
        }
        val old = stat(1, 22, false)
        val modern = stat(9, 44, true)
        val snapshot = pair().copy(statistics = listOf(old, modern))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(), WeaponSelection(increments = mapOf("weapon_361" to 8)))
        listOf(old, modern).forEach { source ->
            val decoded = StatisticCodec.decode(plan.outputs.getValue(source.path))
            val before = StatisticCodec.decode(source.bytes)
            assertEquals(JsonPrimitive(17), decoded["object2ObtainTime"]!!.jsonObject["weapon_361"])
            assertEquals(before["object2ObtainTime"]!!.jsonObject["weapon_362"], decoded["object2ObtainTime"]!!.jsonObject["weapon_362"])
            assertEquals(before["nativeType"], decoded["nativeType"])
            assertEquals(StatisticCodec.format(source.bytes), StatisticCodec.format(plan.outputs.getValue(source.path)))
        }
    }

    @Test fun channelFlagScopeIsPreservedAfterComposingTheItemChanges() {
        val snapshot = pair()
        val prefs = requireNotNull(snapshot.prefs)
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(increments = mapOf(iron to 1)))
        val nodes = UnlockEngine.prefs(plan.outputs[prefs.path] ?: prefs.bytes)
        assertEquals("1", nodes.getValue("OpenRijTest_314").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenNewtonJsonTest_314").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_99").getAttribute("value"))
        assertArrayEquals(prefs.bytes, plan.outputs[prefs.path] ?: prefs.bytes)
        assertTrue(snapshot.items.all { ItemEngine.quantity(ItemCodec.decode(plan.outputs.getValue(it.path)), iron) == 8 })
    }

    @Test fun legacyRouteUsesLegacyIncrementBaselineAndRetainsBothUnselectedBaselines() {
        val snapshot = pair(flag = "0")
        assertFalse(EditEngine.item(snapshot, "42")!!.path.endsWith(".new"))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(increments = mapOf(iron to 1000)))
        snapshot.items.forEach { source ->
            val output = plan.outputs.getValue(source.path)
            val decoded = ItemCodec.decode(output)
            assertEquals(4000, ItemEngine.quantity(decoded, iron))
            assertEquals(if (source.path.endsWith(".new")) 3 else 3000,
                ItemEngine.quantity(decoded, ItemKey("materials", "material_wood")))
            assertEquals(ItemCodec.decode(source.bytes) - "materials", decoded - "materials")
            assertEquals(ItemCodec.format(source.bytes), ItemCodec.format(output))
        }
        val prefs = requireNotNull(snapshot.prefs)
        assertArrayEquals(prefs.bytes, plan.outputs[prefs.path] ?: prefs.bytes)
    }

    @Test fun conflictingPairWithUnknownRouteDoesNotGuessAnIncrementBaseline() {
        for (flag in listOf(null, "2")) {
            val snapshot = pair(flag = flag)
            assertThrows(IllegalArgumentException::class.java) { EditEngine.item(snapshot, "42") }
            assertThrows(IllegalArgumentException::class.java) {
                EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(increments = mapOf(iron to 1000)))
            }
        }
    }

    @Test fun currentOnlySkinSelectionCarriesTheVerifiedUnlockToAStaleCompanion() {
        val skin = WeaponSkinCatalog.forVersion(80700).first()
        val modernRoot = buildJsonObject { put("weapons", buildJsonObject { put(skin.weapon, buildJsonObject {
            put("Name", skin.weapon); put("Level", 0); put("CurrentSkinIndex", 0)
            put("UnlockedSkins", JsonArray(listOf(JsonPrimitive(skin.id))))
        }) }) }
        val legacyRoot = buildJsonObject { put("weapons", JsonObject(emptyMap())); put("legacyOnly", true) }
        val old = file("weapon_evolution_data_42_.data", ItemCodec.encode(legacyRoot))
        val modern = file("weapon_evolution_data_42_.data.new", RijndaelCodec.encode(modernRoot))
        val snapshot = pair().copy(weaponSkins = listOf(old, modern))
        val plan = EditEngine.preview(snapshot, "42", UnlockSelection(), ItemSelection(), WeaponSelection(),
            WeaponSkinSelection(current = mapOf(skin.weapon to skin.index)))
        listOf(old, modern).forEach { source ->
            val record = ItemCodec.decode(plan.outputs.getValue(source.path)).getValue("weapons").jsonObject.getValue(skin.weapon).jsonObject
            assertEquals(JsonPrimitive(skin.index), record["CurrentSkinIndex"])
            assertEquals(JsonArray(listOf(JsonPrimitive(skin.id))), record["UnlockedSkins"])
        }
        assertEquals(JsonPrimitive(true), ItemCodec.decode(plan.outputs.getValue(old.path))["legacyOnly"])
    }
}
