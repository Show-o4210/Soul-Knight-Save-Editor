package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FreeEditEngineTest {
    private val root = "/data/user/0/com.test.game"
    private val original = Json.parseToJsonElement("""{"AppVersion":99999,"future":{"large":900719925474099312345,"text":"900719925474099312345","null":null},"value":1}""").jsonObject
    private fun encoded(codec: FreeCodec, value: JsonObject = original): ByteArray = when (codec) {
        FreeCodec.GAME_JSON -> UnlockEngine.xor(value.toString().toByteArray())
        FreeCodec.ITEM_JSON -> ItemCodec.encode(value)
        FreeCodec.STATISTIC_JSON -> StatisticCodec.encode(value)
        FreeCodec.PLAIN_JSON -> value.toString().toByteArray()
        FreeCodec.RAW -> byteArrayOf(0, -1, 17, 2)
        FreeCodec.PREFS_XML -> error("XML cases use their original text")
    }
    private fun file(name: String = "game.data", codec: FreeCodec = FreeCodec.GAME_JSON) =
        SaveFile("$root/files/$name", encoded(codec), "10100:10100", "600", "u:object_r:app_data_file:s0")
    private fun snapshot(vararg files: SaveFile) = SaveSnapshot("com.test.game", 0, null, null, files.toList())
    private fun export(vararg files: SaveFile) = FreeEditEngine.create(snapshot(*files), files.map { it.path }.toSet(), null, "content://workspace/session")
    private fun changed(document: FreeDocument, value: Int = 2, name: String = document.name) =
        FreeDocument(name, name, document.bytes.toString(Charsets.UTF_8).replace("\"value\": 1", "\"value\": $value").toByteArray())

    @Test fun detectsCodecsWithoutBusinessSchemasOrKnownVersionsAndPreservesUnknownFieldsAndLargeNumbers() {
        FreeCodec.entries.filter { it != FreeCodec.RAW && it != FreeCodec.PREFS_XML }.forEach { codec ->
            val file = file(codec = codec)
            assertEquals(codec, FreeEditEngine.detect(file))
            val (session, documents) = export(file)
            assertTrue(String(documents.single().bytes).contains("900719925474099312345"))
            val edited = changed(documents.single())
            val review = FreeEditEngine.match(session, listOf(edited))
            val plan = FreeEditEngine.plan(session, review)
            val decoded = FreeEditEngine.decode(plan.outputs.getValue(file.path), codec)
            assertEquals(2, decoded.getValue("value").jsonPrimitive.int)
            assertEquals(original.getValue("future"), decoded.getValue("future"))
            assertEquals(99999, decoded.getValue("AppVersion").jsonPrimitive.int)
            assertArrayEquals(encoded(codec), file.bytes)
        }
    }
    @Test fun whitespaceOnlyEditsNeverRewriteEncodedOriginalBytes() {
        FreeCodec.entries.filter { it != FreeCodec.RAW && it != FreeCodec.PREFS_XML }.forEach { codec ->
            val (session, documents) = export(file(codec = codec))
            val doc = documents.single().let { FreeDocument(it.id, it.name, ("\uFEFF  " + original.toString() + "\n").toByteArray()) }
            val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(doc)))
            assertTrue(plan.outputs.isEmpty())
        }
    }
    @Test fun invalidJsonIsReportedBeforeAnyWritePlanAndSemanticValuesAreNotRestricted() {
        val source = file("statistic_42_.data", FreeCodec.STATISTIC_JSON)
        val (session, documents) = export(source)
        val doc = documents.single()
        assertThrows(IllegalArgumentException::class.java) {
            FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(FreeDocument(doc.id, doc.name, "{broken".toByteArray()))))
        }
        val unusual = FreeDocument(doc.id, doc.name, """{"object2ObtainTime":false,"AppVersion":0,"custom":"anything"}""".toByteArray())
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(unusual)))
        assertEquals(false, StatisticCodec.decode(plan.outputs.getValue(source.path)).getValue("object2ObtainTime").jsonPrimitive.boolean)
    }
    @Test fun unknownCodecUsesExplicitRawBytesAndCanBeImportedWithoutParsing() {
        val source = file("future.data", FreeCodec.RAW)
        assertEquals(FreeCodec.RAW, FreeEditEngine.detect(source))
        val (session, documents) = export(source)
        assertTrue(documents.single().name.endsWith(".bin"))
        val updated = byteArrayOf(8, 0, 9, -1)
        val doc = FreeDocument("raw-edit", documents.single().name, updated)
        assertArrayEquals(updated, FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(doc))).outputs.getValue(source.path))
    }
    @Test fun hashMatchesRenamedUnchangedFilesBeforeFilenamePrefix() {
        val second = file("b.data", FreeCodec.STATISTIC_JSON).copy(bytes = encoded(FreeCodec.STATISTIC_JSON, JsonObject(original + ("value" to JsonPrimitive(7)))))
        val (session, documents) = export(file("a.data"), second)
        val renamed = FreeDocument("renamed", "unrelated-name.txt", documents.first().bytes)
        val review = FreeEditEngine.match(session, listOf(renamed))
        assertEquals("哈希", review.matches.single { it.chosen != null }.chosen!!.matchedBy)
        assertEquals(0, review.changed)
        assertTrue(review.ignored.isEmpty())
    }
    @Test fun modifiedCopyWinsOverUnchangedOriginalAndUnknownExtrasStayIgnored() {
        val (session, documents) = export(file())
        val original = documents.single()
        val copy = changed(original, name = original.name + ".bak")
        val noise = FreeDocument("noise", "notes.txt", "unrelated text".toByteArray())
        val review = FreeEditEngine.match(session, listOf(original, copy, noise))
        assertEquals(copy.id, review.matches.single().selected)
        assertEquals("文件名前缀", review.matches.single().chosen!!.matchedBy)
        assertEquals(1, review.changed)
        assertEquals(1, review.ignored.size)
        assertEquals(setOf(session.entries.single().path), FreeEditEngine.plan(session, review).outputs.keys)
    }
    @Test fun conflictingEditedCopiesRequireAChoiceAndCanBeExplicitlySkipped() {
        val (session, documents) = export(file())
        val first = changed(documents.single(), 2)
        val second = changed(documents.single(), 3, documents.single().name + ".copy")
        val review = FreeEditEngine.match(session, listOf(first, second))
        assertEquals(1, review.unresolved)
        assertThrows(IllegalArgumentException::class.java) { FreeEditEngine.plan(session, review) }
        val selected = review.select(session.entries.single().path, second.id)
        assertEquals(3, FreeEditEngine.decode(FreeEditEngine.plan(session, selected).outputs.values.single(), FreeCodec.GAME_JSON).getValue("value").jsonPrimitive.int)
        assertTrue(FreeEditEngine.plan(session, review.select(session.entries.single().path, FreeImportReview.SKIP)).outputs.isEmpty())
    }
    @Test fun identicalCopiesAreDeduplicatedAndBasenameFallbackWorksAfterIndexRemoved() {
        val (session, documents) = export(file())
        val first = changed(documents.single(), name = "game.data.json")
        val second = FreeDocument("copy", "game.data (1).json", first.bytes.copyOf())
        val review = FreeEditEngine.match(session, listOf(first, second))
        assertEquals(0, review.unresolved)
        assertEquals(1, review.changed)
        assertTrue(FreeEditEngine.plan(session, review).outputs.isNotEmpty())
    }
    @Test fun editorMayRemoveDataExtensionAndFormattingKeepsEscapedStringsAndNumbers() {
        val source = file(codec = FreeCodec.PLAIN_JSON).copy(bytes = """{"text":"quoted \" } ],: \\ literal","numbers":[900719925474099312345,1e-999,-0.000],"empty":{},"value":1}""".toByteArray())
        val (session, documents) = export(source)
        val root = Json.parseToJsonElement(String(source.bytes)).jsonObject
        assertEquals(root, Json.parseToJsonElement(String(documents.single().bytes)).jsonObject)
        val renamed = changed(documents.single(), name = "game.json")
        val review = FreeEditEngine.match(session, listOf(renamed))
        assertEquals(1, review.changed)
        val updated = FreeEditEngine.plan(session, review).outputs.getValue(source.path)
        assertTrue(String(updated).contains("900719925474099312345"))
        assertEquals(root.getValue("numbers"), Json.parseToJsonElement(String(updated)).jsonObject.getValue("numbers"))
    }
    @Test fun overlappingFilePrefixesNeverBindToAnotherTargetAndAmbiguousBasenamesAreIgnored() {
        val (session, documents) = export(file("a.data"), file("a.data2.data", FreeCodec.STATISTIC_JSON))
        val edited = changed(documents.last())
        val review = FreeEditEngine.match(session, listOf(edited))
        assertEquals(session.entries.last().path, review.matches.single { it.chosen != null }.entry.path)
        val a = file("game.data")
        val b = a.copy(path = "$root/files/sub/game.data")
        val (duplicate, docs) = export(a, b)
        val ambiguous = FreeDocument("ambiguous", "game.data.json", changed(docs.first()).bytes)
        assertEquals(1, FreeEditEngine.match(duplicate, listOf(ambiguous)).ignored.size)
    }
    @Test fun selectedShardFlagsResetOnlyItsAccountAndNoOtherFileIsWritten() {
        val prefs = SaveFile("$root/shared_prefs/game.xml", """<map><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/><int name="OpenRijTest_99" value="1"/></map>""".toByteArray(), "10100:10100", "600", "")
        val source = file("item_data_42_.data", FreeCodec.ITEM_JSON)
        val untouched = file("statistic_99_.data", FreeCodec.STATISTIC_JSON)
        val snapshot = SaveSnapshot("com.test.game", 0, prefs, null, listOf(source, untouched))
        val (session, docs) = FreeEditEngine.create(snapshot, setOf(source.path), null, "content://workspace/session")
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(changed(docs.single()))))
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("0", nodes.getValue("OpenNewtonJsonTest_42").getAttribute("value"))
        assertEquals("1", nodes.getValue("OpenRijTest_99").getAttribute("value"))
        assertFalse(untouched.path in plan.outputs)
        assertFalse(untouched.path in session.snapshot.files.map { it.path })
    }
    @Test fun unboundFilesRequireExplicitAccountWhenMultipleAccountsExist() {
        val source = file()
        val prefs = file().copy(path = "$root/shared_prefs/game.xml", bytes = """<map><int name="OpenRijTest_42" value="1"/><int name="OpenRijTest_99" value="1"/></map>""".toByteArray())
        val snapshot = snapshot(source, prefs)
        assertThrows(IllegalArgumentException::class.java) { FreeEditEngine.create(snapshot, setOf(source.path), null, "content://workspace/session") }
        assertEquals("99", FreeEditEngine.create(snapshot, setOf(source.path), "99", "content://workspace/session").first.entries.single().account)
    }
}
