package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** XML stays bound to an existing game file, including after restarting an editing session. */
class FreeEditXmlTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = "/data/user/0/com.test.game"
    private fun xml(name: String = "game.xml", text: String = """<map><int name="value" value="1"/></map>""") =
        SaveFile("$root/shared_prefs/$name", text.toByteArray(), "10100:10100", "600", "label")
    private fun data() = xml().copy(path = "$root/files/item_data_42_.data",
        bytes = ItemCodec.encode(kotlinx.serialization.json.Json.parseToJsonElement("""{"value":1}""").jsonObject))
    private fun snapshot(vararg files: SaveFile) = SaveSnapshot("com.test.game", 0, files.firstOrNull { FreeEditEngine.isXml(it.path) },
        null, files.filter { it.path != files.firstOrNull { item -> FreeEditEngine.isXml(item.path) }?.path })
    private fun export(vararg files: SaveFile) = FreeEditEngine.create(snapshot(*files), files.map { it.path }.toSet(), null, "content://workspace/session")
    private fun edit(document: FreeDocument, text: String, name: String = document.name) = FreeDocument(name, name, text.toByteArray())

    @Test fun xmlExportsOriginalTextWithoutJsonSuffixAndUntouchedImportsDoNotRewriteAnything() {
        val source = xml(text = """<?xml version="1.0" encoding="utf-8"?>
<map>
  <!-- Keep formatting and unknown values -->
  <string name="custom">A &amp; B</string>
  <set name="future"><string>one</string><string>two</string></set>
</map>""")
        val (session, docs) = export(source)
        assertEquals(FreeCodec.PREFS_XML, FreeEditEngine.detect(source))
        assertEquals("000_game.xml", docs.single().name)
        assertArrayEquals(source.bytes, docs.single().bytes)
        assertTrue(FreeEditEngine.plan(session, FreeEditEngine.match(session, docs)).outputs.isEmpty())
    }

    @Test fun xmlOnlyChangesPreserveExactTextAndUnknownKeysAndTypes() {
        val source = xml()
        val (session, docs) = export(source)
        val edited = """<map>
  <long name="value" value="9007199254740993123"/>
  <float name="custom" value="1.25"/>
  <boolean name="enabled" value="true"/>
  <string name="text">汉字 &amp; text</string>
  <set name="future"><string>one</string></set>
</map>"""
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit(docs.single(), edited))))
        assertEquals(setOf(source.path), plan.outputs.keys)
        assertArrayEquals(edited.toByteArray(), plan.outputs.getValue(source.path))
        assertEquals("long", UnlockEngine.prefs(plan.outputs.getValue(source.path)).getValue("value").tagName)
        assertArrayEquals("""<map><int name="value" value="1"/></map>""".toByteArray(), source.bytes)
    }

    @Test fun editedXmlIsTheBaseForReadingFlagResetAndMissingFlagsAreNotCreated() {
        val source = xml(text = """<map><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/><int name="OpenRijTest_99" value="1"/><string name="custom">before</string></map>""")
        val (session, docs) = export(source)
        val edited = """<map><int name="OpenRijTest_42" value="1"/><int name="OpenRijTest_99" value="1"/><string name="custom">after</string><long name="newKey" value="33"/></map>"""
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit(docs.single(), edited))))
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(source.path))
        assertEquals("after", nodes.getValue("custom").textContent)
        assertEquals("33", nodes.getValue("newKey").getAttribute("value"))
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("0", nodes.getValue("OpenRijTest_99").getAttribute("value"))
        assertFalse("OpenNewtonJsonTest_42" in nodes)
        assertFalse("OpenNewtonJsonTest_99" in nodes)
    }

    @Test fun importedXmlAndDataShareOnePlanAndUnchangedUnrelatedXmlKeepsItsBytes() {
        val prefs = xml(text = """<map><int name="OpenRijTest_42" value="1"/><string name="custom">before</string></map>""")
        val unrelated = xml("sdk.xml", """<map><string name="sdk">preserve</string></map>""")
        val source = data()
        val (session, docs) = export(source, prefs, unrelated)
        val editedDocs = docs.map { doc ->
            when {
                doc.name.contains("item_data") -> edit(doc, """{"value":2}""")
                doc.name.contains("game.xml") -> edit(doc, """<map><int name="OpenRijTest_42" value="1"/><string name="custom">after</string></map>""")
                else -> doc
            }
        }
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, editedDocs))
        assertEquals(setOf(source.path, prefs.path), plan.outputs.keys)
        assertEquals(2, ItemCodec.decode(plan.outputs.getValue(source.path)).getValue("value").jsonPrimitive.int)
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
        assertEquals("after", nodes.getValue("custom").textContent)
        assertEquals("0", nodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertFalse(unrelated.path in plan.outputs)
    }

    @Test fun tryingToReenableAReadingFlagNeverLeavesAnUnmergedOutput() {
        val source = xml(text = """<map><int name="OpenRijTest_42" value="0"/></map>""")
        val (session, docs) = export(source)
        val edited = edit(docs.single(), """<map><int name="OpenRijTest_42" value="1"/></map>""")
        assertTrue(FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edited))).outputs.isEmpty())
    }

    @Test fun malformedOrExternallyDeclaredXmlIsRejectedBeforeMakingAWritePlan() {
        val (session, docs) = export(xml())
        listOf("<map><int", "<preferences/>", """<map><int name="x" value="1"/><long name="x" value="2"/></map>""",
            """<!DOCTYPE map [<!ENTITY external SYSTEM "file:///private">]><map><string name="x">&external;</string></map>""").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit(docs.single(), text))))
            }
        }
        val brokenEncoding = FreeDocument("invalid-utf8", docs.single().name, byteArrayOf(0xFF.toByte()))
        assertThrows(IllegalArgumentException::class.java) { FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(brokenEncoding))) }
    }

    @Test fun xmlMatchingUsesHashesThenPrefixesAndExtraXmlCannotChooseANewWritePath() {
        val source = xml()
        val (session, docs) = export(source)
        val renamed = FreeDocument("renamed", "arbitrary.xml", docs.single().bytes)
        assertEquals("哈希", FreeEditEngine.match(session, listOf(renamed)).matches.single().chosen!!.matchedBy)
        val modified = edit(docs.single(), """<map><int name="value" value="2"/></map>""", "game (1).xml")
        val injected = FreeDocument("unexpected", "../../shared_prefs/other.xml", "<map/>".toByteArray())
        val review = FreeEditEngine.match(session, listOf(modified, injected))
        assertEquals(1, review.ignored.size)
        assertEquals("文件名前缀", review.matches.single().chosen!!.matchedBy)
        assertEquals(setOf(source.path), FreeEditEngine.plan(session, review).outputs.keys)
        val outside = source.copy(path = "/data/user/0/com.other.game/shared_prefs/game.xml")
        assertThrows(IllegalStateException::class.java) { export(outside) }
    }

    @Test fun multipleXmlOriginalsAndEntriesSurviveSessionRestartIncludingXmlOnlyMultiAccountFiles() {
        val first = xml(text = """<map><int name="OpenRijTest_42" value="0"/><int name="OpenRijTest_99" value="0"/></map>""")
        val second = xml("sdk.xml", """<map><string name="sdk">anything</string></map>""")
        val (session, _) = export(first, second)
        val store = FreeEditStore(temp.newFolder())
        store.save(session)
        val loaded = store.load(session.id)
        assertEquals(session.entries, loaded.entries)
        assertEquals(setOf(first.path, second.path), loaded.snapshot.files.map { it.path }.toSet())
        loaded.snapshot.files.forEach { file -> assertArrayEquals(session.snapshot.files.single { it.path == file.path }.bytes, file.bytes) }
    }

    private inner class Access(backend: AccessBackend, originals: List<SaveFile>) : BaseSaveAccess(backend) {
        val files = originals.associateBy { it.path }.toMutableMap()
        var writes = 0
        var failAt = -1
        override fun ensureReady() {}
        override fun stop(packageName: String, user: Int) {}
        override fun exists(directory: String) = true
        override fun list(root: String, source: SaveSource) = files.keys.filter { it.startsWith("$root/${source.directory}/") && source.filename.matches(it.substringAfterLast('/')) }
        override fun read(path: String) = files.getValue(path)
        override fun replace(file: SaveFile, bytes: ByteArray) {
            writes++
            if (writes == failAt) error("simulated second-file failure")
            files[file.path] = file.copy(bytes = bytes)
        }
        override fun packages(user: Int) = ""
        override fun probePaths(packageName: String, user: Int) = emptyList<String>()
        override fun version(packageName: String) = "8.7.0"
        override fun backupPaths(packageName: String, user: Int) = files.keys.filter { it.endsWith(".data") }
        override fun readBackup(path: String) = read(path)
        override fun close() {}
    }

    @Test fun bothTransportsCaptureAllSharedPrefsAndCanReadXmlOnlySaves() {
        AccessBackend.entries.forEach { backend ->
            val ordinary = xml("ordinary.xml", """<map><string name="unrecognized">preserve</string></map>""")
            val other = xml("another.xml", "<map/>")
            val access = Access(backend, listOf(ordinary, other))
            val snapshot = access.freeSnapshot("com.test.game", 0)
            assertEquals(setOf(ordinary.path, other.path), snapshot.files.map { it.path }.toSet())
            val (session, docs) = FreeEditEngine.create(snapshot, setOf(ordinary.path), null, "content://workspace/session")
            val review = FreeEditEngine.match(session, listOf(edit(docs.single(), """<map><string name="unrecognized">updated</string></map>""")))
            val repository = SaveRepository(temp.newFolder(), access)
            val id = repository.apply(session.snapshot, FreeEditEngine.plan(session, review))
            assertEquals(backend.id, repository.backendId(id))
            assertEquals("updated", UnlockEngine.prefs(access.read(ordinary.path).bytes).getValue("unrecognized").textContent)
            assertArrayEquals(other.bytes, access.read(other.path).bytes)
        }
    }

    @Test fun editedXmlAndDataRollbackTogetherAndXmlDriftPreventsAllWritesOnBothTransports() {
        AccessBackend.entries.forEach { backend ->
            val prefs = xml(text = """<map><int name="OpenRijTest_42" value="1"/><string name="custom">before</string></map>""")
            val source = data()
            val access = Access(backend, listOf(prefs, source))
            val (session, docs) = export(prefs, source)
            val edited = docs.map { doc ->
                if (doc.name.contains(".xml")) edit(doc, """<map><int name="OpenRijTest_42" value="1"/><string name="custom">after</string></map>""")
                else edit(doc, """{"value":2}""")
            }
            val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, edited))
            val repository = SaveRepository(temp.newFolder(), access)
            access.failAt = 2
            assertThrows(IllegalStateException::class.java) { repository.apply(session.snapshot, plan) }
            assertArrayEquals(prefs.bytes, access.read(prefs.path).bytes)
            assertArrayEquals(source.bytes, access.read(source.path).bytes)
            assertTrue(repository.pending().isEmpty())

            access.failAt = -1
            access.writes = 0
            val drift = """<map><string name="newProgress">outside edit</string></map>""".toByteArray()
            access.files[prefs.path] = prefs.copy(bytes = drift)
            assertThrows(IllegalArgumentException::class.java) { repository.apply(session.snapshot, plan) }
            assertEquals(0, access.writes)
            assertArrayEquals(drift, access.read(prefs.path).bytes)
        }
    }
}
