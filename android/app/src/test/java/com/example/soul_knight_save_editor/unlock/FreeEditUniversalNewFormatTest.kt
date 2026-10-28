package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FreeEditUniversalNewFormatTest {
    private val root = "/data/user/0/com.test.game"
    private val shardTypes = listOf("item_data", "statistic", "season_data", "setting", "mall_reload_data", "task",
        "bp_data", "weapon_evolution_data", "escape_season_data", "misc_data", "monsrise_data", "pvp_data")
    private val original = Json.parseToJsonElement(
        """{"value":1,"future":[1,null,"测试"],"untouched":{"enabled":true,"text":""}}""").jsonObject
    // PyCryptodome AES-CBC vector, independent of RijndaelCodec.encode: BOM + 77 UTF-8
    // JSON bytes are aligned to 80 bytes, followed by a full block of sixteen zero bytes.
    private val fixture = Base64.decode("emxejeO9z5n1LAkuNLdfFou/V1j1uWrkWSMsH/c8MA2vypxYu8QlHcSMZUvQvvyys9HPfd0pcecT9cRxBtJQT1GRoaO5EPLOqXFjX9zx6YmG7y9v0/fEz8Jc0zym1Y6p")
    private fun file(type: String, account: String = "42") =
        SaveFile("$root/files/${type}_${account}_.data.new", fixture.copyOf(), "10100:10100", "600", "")
    private fun prefs() = SaveFile("$root/shared_prefs/player.xml",
        """<map><int name="OpenRijTest_42" value="1"/><int name="OpenRijTest_99" value="1"/><string name="custom">unchanged</string></map>""".toByteArray(),
        "10100:10100", "600", "")
    private fun export(source: SaveFile, other: SaveFile): Pair<FreeEditSession, List<FreeDocument>> =
        FreeEditEngine.create(SaveSnapshot("com.test.game", 0, prefs(), null, listOf(source, other)),
            setOf(source.path), "42", "content://private/session")

    private fun independentlyDecoded(bytes: ByteArray): JsonObject {
        val key = SecretKeySpec("4ff564b5efa57ea7d320a714b6baad8a307a426c15af0e9a40709eb050ae3c40".hexToByteArray(), "AES")
        val iv = IvParameterSpec("198a9eb7a7f8e766b37fd176fd8aaaa9".hexToByteArray())
        val plain = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, iv)
            doFinal(bytes)
        }
        assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), plain.copyOfRange(0, 3))
        val end = plain.indexOfLast { it != 0.toByte() } + 1
        assertTrue(plain.size - end in 0..16)
        return Json.parseToJsonElement(plain.copyOfRange(3, end).toString(Charsets.UTF_8)).jsonObject
    }

    @Test fun everyShardTypeExportsFullBlockPaddedAesAsJsonWithoutBusinessFieldRequirements() {
        assertEquals(96, fixture.size)
        assertEquals(original, independentlyDecoded(fixture))
        shardTypes.forEach { type ->
            val source = file(type)
            val other = file(type, "99")
            assertTrue(type, FreeEditEngine.exportable(source))
            assertEquals(type, FreeCodec.ITEM_JSON, FreeEditEngine.detect(source))
            val (session, documents) = export(source, other)
            assertTrue(type, session.keepReadingModes)
            assertTrue(type, documents.single().name.endsWith(".data.new.json"))
            assertEquals(type, original, Json.parseToJsonElement(documents.single().bytes.toString(Charsets.UTF_8)))
            assertFalse(type, session.snapshot.files.any { it.path == other.path })
        }
    }

    @Test fun oneFieldImportKeepsNativeEncodingUnselectedFieldsOtherAccountsAndReadingFlags() {
        shardTypes.forEach { type ->
            val source = file(type)
            val other = file(type, "99")
            val (session, documents) = export(source, other)
            val changed = JsonObject(original + ("value" to JsonPrimitive(2)))
            val edited = documents.single().copy(bytes = changed.toString().toByteArray(Charsets.UTF_8))
            val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edited)))
            assertEquals(type, setOf(source.path), plan.outputs.keys)
            val output = plan.outputs.getValue(source.path)
            assertEquals(type, changed, independentlyDecoded(output))
            assertEquals(type, ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(output))
            assertEquals(type, original - "value", ItemCodec.decode(output) - "value")
            assertArrayEquals(type, fixture, source.bytes)
            assertArrayEquals(type, fixture, other.bytes)
            assertTrue(type, plan.outputs.keys.none { it.contains("/shared_prefs/") })
        }
    }

    @Test fun unchangedOrOnlyReformattedImportsRetainExactOriginalCiphertextForEveryType() {
        shardTypes.forEach { type ->
            val source = file(type)
            val (session, documents) = export(source, file(type, "99"))
            val unchanged = FreeEditEngine.plan(session, FreeEditEngine.match(session, documents))
            assertTrue(type, unchanged.outputs.isEmpty())
            val compact = documents.single().copy(bytes = original.toString().toByteArray(Charsets.UTF_8))
            assertFalse(type, compact.bytes.contentEquals(documents.single().bytes))
            val reformatted = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(compact)))
            assertTrue(type, reformatted.outputs.isEmpty())
            assertArrayEquals(type, fixture, source.bytes)
            assertArrayEquals(type, fixture, session.snapshot.files.single { it.path == source.path }.bytes)
        }
    }
}
