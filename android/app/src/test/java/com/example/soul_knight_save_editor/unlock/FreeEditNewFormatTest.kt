package com.example.soul_knight_save_editor.unlock

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FreeEditNewFormatTest {
    @get:Rule val temp = TemporaryFolder()
    private val root = "/data/user/0/com.test.game"
    private fun value(marker: String, iron: Int = 3, wood: Int = 4) = buildJsonObject {
        put("AppVersion", 80700)
        put("materials", buildJsonObject { put("material_iron", iron); put("material_wood", wood) })
        put("privateToFormat", marker)
        put("array", buildJsonArray { add(1); add(2) })
    }
    private fun file(name: String, bytes: ByteArray) = SaveFile("$root/files/$name", bytes, "10100:10100", "600", "")
    private fun pair() = listOf(file("item_data_42_.data", ItemCodec.encode(value("old", 1))),
        file("item_data_42_.data.new", RijndaelCodec.encode(value("new", 9))))
    private fun snapshot(files: List<SaveFile>) = SaveSnapshot("com.test.game", 0, null, null, files)
    private fun export(files: List<SaveFile>, paths: Set<String>) = FreeEditEngine.create(snapshot(files), paths, "42", "content://private/session")
    private fun edited(document: FreeDocument, changes: (JsonObject) -> JsonObject): FreeDocument {
        val json = Json.parseToJsonElement(document.bytes.toString(Charsets.UTF_8)).jsonObject
        return document.copy(bytes = changes(json).toString().toByteArray())
    }
    private fun amount(root: JsonObject, id: String, number: Int) = JsonObject(root + ("materials" to
        JsonObject(root.getValue("materials").jsonObject + (id to JsonPrimitive(number)))))

    @Test fun oneExportPrivatelyCapturesCounterpartAndImportPreservesBothOriginalFormatsAndUnknownFields() {
        for (selectedIndex in 0..1) {
            val files = pair()
            val (session, documents) = export(files, setOf(files[selectedIndex].path))
            assertEquals(1, documents.size)
            assertEquals(files.map { it.path }.toSet(), session.snapshot.files.map { it.path }.toSet())
            val edit = edited(documents.single()) { amount(it, "material_iron", 777) }
            val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit)))
            assertEquals(files.map { it.path }.toSet(), plan.outputs.keys)
            assertEquals(ShardEncoding.DES_BASE64, ItemCodec.format(plan.outputs.getValue(files[0].path)))
            assertEquals(ShardEncoding.RIJNDAEL_JSON, ItemCodec.format(plan.outputs.getValue(files[1].path)))
            files.forEachIndexed { index, source ->
                val actual = ItemCodec.decode(plan.outputs.getValue(source.path))
                assertEquals(777, actual.getValue("materials").jsonObject.getValue("material_iron").jsonPrimitive.int)
                assertEquals(if (index == 0) "old" else "new", actual.getValue("privateToFormat").jsonPrimitive.content)
                assertEquals(4, actual.getValue("materials").jsonObject.getValue("material_wood").jsonPrimitive.int)
            }
        }
    }

    @Test fun compatibleChangesFromBothExportsMergeIntoEachFormat() {
        val files = pair()
        val (session, documents) = export(files, files.map { it.path }.toSet())
        val docs = documents.map { document -> edited(document) { original ->
            if (document.name.contains(".data.new")) amount(original, "material_iron", 111)
            else amount(original, "material_wood", 222)
        } }
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, docs))
        files.forEach { file ->
            val root = ItemCodec.decode(plan.outputs.getValue(file.path))
            assertEquals(111, root.getValue("materials").jsonObject.getValue("material_iron").jsonPrimitive.int)
            assertEquals(222, root.getValue("materials").jsonObject.getValue("material_wood").jsonPrimitive.int)
        }
    }

    @Test fun conflictingChangesFromBothExportsAreRejectedBeforeAnyPlanIsReturned() {
        val files = pair()
        val (session, documents) = export(files, files.map { it.path }.toSet())
        val docs = documents.mapIndexed { index, document -> edited(document) { amount(it, "material_iron", index + 101) } }
        val error = assertThrows(IllegalArgumentException::class.java) { FreeEditEngine.plan(session, FreeEditEngine.match(session, docs)) }
        assertTrue(error.message!!.contains("冲突"))
        files.zip(pair()).forEach { (before, stillOriginal) -> assertArrayEquals(before.bytes, stillOriginal.bytes) }
    }

    @Test fun recoveryCandidatesAreBackupOnlyAndUnknownCounterpartsAreKeptWithExplicitNotice() {
        val files = pair()
        for (suffix in listOf("_temp", ".new_temp")) {
            val native = file("item_data_42_.data$suffix", byteArrayOf(0, -1, 2))
            assertFalse(FreeEditEngine.exportable(native))
            assertTrue(SaveLayout.restorablePath.matches(native.path))
            assertFalse(SaveLayout.editablePath.matches(native.path))
            assertThrows(IllegalArgumentException::class.java) { export(files + native, setOf(native.path)) }
        }
        val unknown = files[1].copy(bytes = byteArrayOf(0, -1, 7))
        val (session, documents) = export(listOf(files[0], unknown), setOf(files[0].path))
        val edit = edited(documents.single()) { amount(it, "material_iron", 88) }
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit)))
        assertEquals(setOf(files[0].path), plan.outputs.keys)
        assertTrue(plan.changes.any { "对应编码未识别" in it })
    }

    @Test fun xmlExportUsesMappedFileAccountsAndResetsTheirActualUidScopesOnImport() {
        val text = """<map><string name="AccountSaveSettingsIds">{"42":"619010001"}</string><string name="42_c0_unlock">true</string><int name="OpenRijTest_619010001" value="1"/><int name="OpenNewtonJsonTest_619010001" value="1"/><string name="custom">before</string></map>"""
        val prefs = SaveFile("$root/shared_prefs/player.xml", text.toByteArray(), "10100:10100", "600", "")
        val snapshot = SaveSnapshot("com.test.game", 0, prefs, null)
        assertEquals(listOf("42"), FreeEditEngine.accounts(snapshot))
        val (session, documents) = FreeEditEngine.create(snapshot, setOf(prefs.path), "42", "content://private/session")
        val doc = documents.single().let { it.copy(bytes = String(it.bytes).replace(">before<", ">after<").toByteArray()) }
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(doc)))
        val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
        assertEquals("after", nodes.getValue("custom").textContent)
        assertEquals("0", nodes.getValue("OpenRijTest_619010001").getAttribute("value"))
        assertEquals("0", nodes.getValue("OpenNewtonJsonTest_619010001").getAttribute("value"))
        assertFalse("OpenRijTest_42" in nodes)
    }

    private fun prefs() = SaveFile("$root/shared_prefs/player.xml", """<map><string name="42_c0_unlock">true</string><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/><string name="custom">before</string></map>""".toByteArray(), "10100:10100", "600", "")

    @Test fun newFormatJsonImportKeepsOriginalReadingModesInsteadOfSelectingStaleLegacyFields() {
        val files = pair()
        val prefs = prefs()
        val (session, documents) = FreeEditEngine.create(SaveSnapshot("com.test.game", 0, prefs, null, files),
            setOf(files[1].path), "42", "content://private/session")
        assertTrue(session.keepReadingModes)
        val edit = edited(documents.single()) { amount(it, "material_iron", 888) }
        val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, listOf(edit)))
        assertEquals(files.map { it.path }.toSet(), plan.outputs.keys)
        val originalNodes = UnlockEngine.prefs(prefs.bytes)
        assertEquals("1", originalNodes.getValue("OpenRijTest_42").getAttribute("value"))
        assertEquals("1", originalNodes.getValue("OpenNewtonJsonTest_42").getAttribute("value"))
    }

    @Test fun xmlOnlyExportRemembersSourceNewFormatAfterRestartAndKeepsExplicitUserFlagEdits() {
        val prefs = prefs()
        val (session, documents) = FreeEditEngine.create(SaveSnapshot("com.test.game", 0, prefs, null, pair()),
            setOf(prefs.path), "42", "content://private/session")
        assertTrue(session.snapshot.files.none { it.path.endsWith(".data.new") })
        assertTrue(session.keepReadingModes)
        val store = FreeEditStore(temp.newFolder())
        store.save(session)
        val loaded = store.load(session.id)
        assertTrue(loaded.keepReadingModes)
        for (explicitZero in listOf(false, true)) {
            val doc = documents.single().let {
                val changed = String(it.bytes).replace(">before<", ">after<")
                it.copy(bytes = (if (explicitZero) changed.replace("value=\"1\"", "value=\"0\"") else changed).toByteArray())
            }
            val plan = FreeEditEngine.plan(loaded, FreeEditEngine.match(loaded, listOf(doc)))
            val nodes = UnlockEngine.prefs(plan.outputs.getValue(prefs.path))
            assertEquals("after", nodes.getValue("custom").textContent)
            assertEquals(if (explicitZero) "0" else "1", nodes.getValue("OpenRijTest_42").getAttribute("value"))
            assertEquals(if (explicitZero) "0" else "1", nodes.getValue("OpenNewtonJsonTest_42").getAttribute("value"))
        }
    }
}
