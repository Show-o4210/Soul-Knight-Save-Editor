package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RijndaelCodecTest {
    private val fixtureRoot = Json.parseToJsonElement(
        """{"AppVersion":80700,"materials":{"material_iron":7},"future":[]}""").jsonObject
    // Independently generated with PyCryptodome from anonymous JSON and the verified native constants.
    private val fixture = Base64.decode("ig84+rtF7GpRAlnGLdljgEWg2GfBoP7LxJtxgJdnsvGTuGxuhVAPNCyYZY5vRo4s3qyaXJD4WLPIFCWcIjzAmcRTVO5eyDgEcwyAExfZiVc=")

    @Test fun fixedNativeVectorAndAlignedBlockMatchIndependentCiphertextExactly() {
        assertEquals(fixtureRoot, RijndaelCodec.decode(fixture))
        assertArrayEquals(fixture, RijndaelCodec.encode(fixtureRoot))
        val aligned = Json.parseToJsonElement("""{"a":1234567}""").jsonObject
        val alignedBytes = Base64.decode("XyLcedMd4zsj+AP/ia5ObQ==")
        assertEquals(16, alignedBytes.size)
        assertEquals(aligned, RijndaelCodec.decode(alignedBytes))
        assertArrayEquals(alignedBytes, RijndaelCodec.encode(aligned))
    }

    @Test fun bothShardCodecsRecognizeNativeCiphertextAndPreserveItsFormatOnEdit() {
        assertEquals(fixtureRoot, ItemCodec.decode(fixture))
        assertEquals(fixtureRoot, StatisticCodec.decode(fixture))
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(fixture))
        assertEquals(ShardEncoding.RIJNDAEL_JSON, StatisticCodec.format(fixture))
        val changed = JsonObject(fixtureRoot + ("未来字段" to JsonPrimitive("保留中文编码")))
        for (bytes in listOf(ItemCodec.encode(changed, fixture), StatisticCodec.encode(changed, fixture))) {
            assertEquals(changed, RijndaelCodec.decode(bytes))
            assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(bytes))
        }
        assertSame(fixture, ItemCodec.encode(fixtureRoot, fixture))
        assertSame(fixture, StatisticCodec.encode(fixtureRoot, fixture))
    }

    @Test fun legacyDefaultsAndWhitespaceEnvelopeKeepTheirExactNoOpBytes() {
        val oldItem = ItemCodec.encode(fixtureRoot)
        val oldStat = StatisticCodec.encode(fixtureRoot)
        assertEquals(ShardEncoding.DES_BASE64, ItemCodec.format(oldItem))
        assertEquals(ShardEncoding.DES_BASE64, StatisticCodec.format(oldStat))
        val wrapped = (" \r\n" + oldItem.toString(Charsets.UTF_8).chunked(11).joinToString("\r\n") + "\t").toByteArray()
        assertEquals(fixtureRoot, ItemCodec.decode(wrapped))
        assertSame(wrapped, ItemCodec.encode(fixtureRoot, wrapped))
        val changed = JsonObject(fixtureRoot + ("keepArray" to JsonArray(listOf(JsonPrimitive(1)))))
        assertEquals(ShardEncoding.DES_BASE64, ItemCodec.format(ItemCodec.encode(changed, wrapped)))
        assertEquals(ShardEncoding.DES_BASE64, StatisticCodec.format(StatisticCodec.encode(changed, oldStat)))
        assertEquals(changed, ItemCodec.decode(ItemCodec.encode(changed, wrapped)))
    }

    @Test fun unknownCiphertextMissingBomInvalidUtf8ExcessPaddingAndNonObjectAreRejected() {
        val invalid = listOf(
            fixture.copyOf(fixture.size - 1), ByteArray(16), ByteArray(UnlockEngine.MAX_BYTES + 16),
            // Valid AES-CBC but no BOM; no fallback to an unverified plaintext encoding.
            Base64.decode("HMxS7QtCfoEWuftWl4paiUfHrKOLkhrxLyayc+bfqNnjEbbSCt8uEofBqVPmAG+v9jnRZTO4aDyIz5zmAEiGng=="),
            Base64.decode("+vaRdXfYKjKvu+CHeNpa9Q=="), // JSON array
            Base64.decode("dFFdsIR3M8Q7W2Nl0Bmk2g=="), // malformed UTF-8
            Base64.decode("YZRxm2+CElXpLbqphlU/W+VSQzFOobeJkSJ701g2+K0=")) // full surplus zero block
        invalid.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { RijndaelCodec.decode(bytes) }
            assertThrows(IllegalArgumentException::class.java) { ItemCodec.decode(bytes) }
            assertThrows(IllegalArgumentException::class.java) { StatisticCodec.encode(fixtureRoot, bytes) }
        }
    }

    @Test fun malformedAsciiBase64NeverReinterpretsAsNativeCiphertext() {
        val invalidAscii = listOf("not_base64", "AAAAAAAAAAAAAAAA", Base64.encode(fixture))
        invalidAscii.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { ItemCodec.decode(text.toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { StatisticCodec.decode(text.toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { ItemCodec.encode(fixtureRoot, text.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RijndaelCodec.encode(buildJsonObject { put("oversize", "x".repeat(UnlockEngine.MAX_BYTES)) })
        }
    }

    @Test fun itemAndMythicEditsRetainNativeArraysNullsAndUnselectedFields() {
        val root = Json.parseToJsonElement("""{"AppVersion":80700,"materials":{"material_iron":7},"mythicWeapons":[],"futureArray":[],"futureNull":null,"futureObject":{},"futureText":"unchanged"}""").jsonObject
        val input = RijndaelCodec.encode(root)
        val key = ItemKey("materials", "material_iron")
        val item = ItemEngine.edit(input, ItemSelection(amounts = mapOf(key to 3000)))
        assertEquals(3000, ItemEngine.quantity(item.root, key))
        assertEquals(root - "materials", item.root - "materials")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(item.bytes))
        val mythic = MythicEngine.edit(item.bytes, MythicSelection(unlock = setOf(MythicCatalog.forVersion(80700).first().id)))
        assertEquals(item.root - "mythicWeapons", mythic.root - "mythicWeapons")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(mythic.bytes))
        val unchanged = ItemEngine.edit(input, ItemSelection(amounts = mapOf(key to 7)))
        assertArrayEquals(input, unchanged.bytes)
    }

    @Test fun obtainCountersSkinAndEvolutionAllPreserveNativeEncodingAndUnselectedFields() {
        val item = RijndaelCodec.encode(fixtureRoot)
        val stat = Json.parseToJsonElement("""{"object2ObtainTime":{"weapon_001":1},"futureArray":[],"futureString":"1","futureNull":null}""").jsonObject
        val obtain = WeaponEngine.edit(RijndaelCodec.encode(stat), item,
            WeaponSelection(increments = mapOf("weapon_001" to 8)))
        assertEquals(JsonPrimitive(9), obtain.root.getValue("object2ObtainTime").jsonObject["weapon_001"])
        assertEquals(stat - "object2ObtainTime", obtain.root - "object2ObtainTime")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, StatisticCodec.format(obtain.bytes))
        val evolutionIds = WeaponEvolutionCatalog.forVersion(80700).map { it.id }.toSet()
        val skin = WeaponSkinCatalog.forVersion(80700).first { it.weapon in evolutionIds }
        val weapons = Json.parseToJsonElement("""{"weapons":{},"favorWeapons":[],"futureArray":[],"futureNull":null}""").jsonObject
        val unlocked = WeaponSkinEngine.edit(RijndaelCodec.encode(weapons), item,
            WeaponSkinSelection(unlock = setOf(skin.id), current = mapOf(skin.weapon to skin.index)))
        assertEquals(weapons - "weapons", unlocked.root - "weapons")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(unlocked.bytes))
        val evolved = WeaponEvolutionEngine.edit(unlocked.bytes, item, WeaponEvolutionSelection(levels = mapOf(skin.weapon to 1)))
        assertEquals(unlocked.root - "weapons", evolved.root - "weapons")
        val record = evolved.root.getValue("weapons").jsonObject.getValue(skin.weapon).jsonObject
        assertEquals(JsonPrimitive(1), record["Level"])
        assertEquals(JsonPrimitive(skin.index), record["CurrentSkinIndex"])
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(evolved.bytes))
    }

    @Test fun externalNativeBaselineCanBeDecodedAndEditedInMemoryWithoutChangingAnyFile() {
        val folder = System.getenv("BILI_NEW_BASELINE")?.let(::File)
        assumeTrue("Optional private native-format baseline", folder?.isDirectory == true)
        val directory = requireNotNull(folder)
        val paths = listOf("item_data_ACCOUNT_.data.new", "statistic_ACCOUNT_.data.new", "weapon_evolution_data_ACCOUNT_.data.new")
            .map { File(directory, it) }
        assumeTrue(paths.all(File::isFile))
        val originals = paths.associateWith(File::readBytes)
        val itemBytes = originals.getValue(paths[0])
        val item = ItemEngine.inspect(itemBytes)
        assertEquals(JsonPrimitive(80700), item["AppVersion"])
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(itemBytes))
        val iron = ItemKey("materials", "material_iron")
        val editedItem = ItemEngine.edit(itemBytes, ItemSelection(increments = mapOf(iron to 1)))
        assertEquals(ItemEngine.quantity(item, iron) + 1, ItemEngine.quantity(editedItem.root, iron))
        assertEquals(item - "materials", editedItem.root - "materials")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(editedItem.bytes))
        val statistics = StatisticCodec.decode(originals.getValue(paths[1]))
        assertTrue(statistics["object2ObtainTime"] is JsonObject)
        assertEquals(ShardEncoding.RIJNDAEL_JSON, StatisticCodec.format(originals.getValue(paths[1])))
        val editedStatistics = WeaponEngine.edit(originals.getValue(paths[1]), itemBytes,
            WeaponSelection(increments = mapOf("weapon_001" to 8)))
        assertEquals(statistics - "object2ObtainTime", editedStatistics.root - "object2ObtainTime")
        assertEquals(ShardEncoding.RIJNDAEL_JSON, StatisticCodec.format(editedStatistics.bytes))
        val weaponBytes = originals.getValue(paths[2])
        val weapon = ItemCodec.decode(weaponBytes)
        assertTrue(weapon["weapons"] is JsonObject)
        assertSame(weaponBytes, ItemCodec.encode(weapon, weaponBytes))
        originals.forEach { (path, original) -> assertArrayEquals(original, path.readBytes()) }
    }
}
