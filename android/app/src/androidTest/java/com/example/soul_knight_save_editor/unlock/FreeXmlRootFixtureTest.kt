package com.example.soul_knight_save_editor.unlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicitly opt in; synthetic originals and journals stay inside this assistant's sandbox. */
@RunWith(AndroidJUnit4::class)
class FreeXmlRootFixtureTest {
    @Test fun freeXmlAndDataWriteAndRestoreThroughNativeRootInOwnSandbox() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.example.soul_knight_save_editor")
        val user = android.os.Process.myUid() / 100000
        val token = UUID.randomUUID().toString()
        val fixture = File(context.filesDir, "free-xml-fixture-$token")
        check(fixture.mkdir())
        require(fixture.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val prefsDirectory = File(context.applicationInfo.dataDir, "shared_prefs").apply { check(isDirectory || mkdirs()) }
        val prefs = File(prefsDirectory, "free-xml-fixture-$token.xml")
        val item = File(fixture, "item_data_42_.data")
        var createdPrefs = false
        val root = RootStorage()
        try {
            check(prefs.createNewFile())
            createdPrefs = true
            check(item.createNewFile())
            val originalXml = """<?xml version="1.0" encoding="utf-8"?>
<map><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/><string name="custom">before</string><long name="keep" value="9007199254740993"/></map>""".toByteArray()
            val editedXml = String(originalXml, Charsets.UTF_8).replace(">before</string>", ">after</string>").toByteArray()
            val originalData = ItemCodec.encode(Json.parseToJsonElement("""{"AppVersion":80700,"custom":1,"future":{"keep":true}}""").jsonObject)
            prefs.writeBytes(originalXml)
            item.writeBytes(originalData)
            val allowed = setOf(prefs.absolutePath, item.absolutePath)
            val storage = object : DeviceStorage {
                override val backendId: String get() = root.backendId
                // Do not force-stop the instrumentation or open the game.
                override fun stop(packageName: String, user: Int) { require(packageName == context.packageName && user == android.os.Process.myUid() / 100000) }
                override fun assertIdleForRecovery() = root.assertIdleForRecovery()
                override fun read(path: String): SaveFile { require(path in allowed); return root.read(path) }
                override fun replace(file: SaveFile, bytes: ByteArray) { require(file.path in allowed); root.replace(file, bytes) }
            }
            val snapshot = SaveSnapshot(context.packageName, user, storage.read(prefs.absolutePath), null,
                listOf(storage.read(item.absolutePath)))
            val (session, documents) = FreeEditEngine.create(snapshot, allowed, "42", "content://synthetic-free-xml/$token")
            val imported = documents.map { document ->
                val bytes = if (document.name.endsWith(".xml")) editedXml
                    else """{"AppVersion":80700,"custom":2,"future":{"keep":true}}""".toByteArray()
                FreeDocument(document.id, document.name, bytes)
            }
            val plan = FreeEditEngine.plan(session, FreeEditEngine.match(session, imported))
            assertEquals(allowed, plan.outputs.keys)
            val expectedEditedXml = UnlockEngine.localReadingFlags(editedXml, "42").first
            assertArrayEquals(expectedEditedXml, plan.outputs.getValue(prefs.absolutePath))
            val repository = SaveRepository(File(fixture, "journals"), storage)
            val id = repository.apply(session.snapshot, plan)
            assertEquals(AccessBackend.NATIVE_ROOT.id, repository.backendId(id))
            assertArrayEquals(plan.outputs.getValue(item.absolutePath), storage.read(item.absolutePath).bytes)
            assertArrayEquals(expectedEditedXml, storage.read(prefs.absolutePath).bytes)
            val editedNodes = UnlockEngine.prefs(storage.read(prefs.absolutePath).bytes)
            assertEquals("after", editedNodes.getValue("custom").textContent)
            assertEquals("0", editedNodes.getValue("OpenRijTest_42").getAttribute("value"))
            assertEquals("0", editedNodes.getValue("OpenNewtonJsonTest_42").getAttribute("value"))
            snapshot.files.forEach { original ->
                val actual = storage.read(original.path)
                assertEquals(original.owner, actual.owner)
                assertEquals(original.mode, actual.mode)
                assertEquals(original.context, actual.context)
            }

            repository.restore(id)
            assertArrayEquals(originalData, storage.read(item.absolutePath).bytes)
            // Restore uses the original XML text and retains the required local-reading flags.
            assertArrayEquals(UnlockEngine.localReadingFlags(originalXml, "42").first, storage.read(prefs.absolutePath).bytes)
            val restoredNodes = UnlockEngine.prefs(storage.read(prefs.absolutePath).bytes)
            assertEquals("before", restoredNodes.getValue("custom").textContent)
            assertEquals("9007199254740993", restoredNodes.getValue("keep").getAttribute("value"))
            assertEquals("0", restoredNodes.getValue("OpenRijTest_42").getAttribute("value"))
            assertEquals("0", restoredNodes.getValue("OpenNewtonJsonTest_42").getAttribute("value"))
            snapshot.files.forEach { original ->
                val actual = storage.read(original.path)
                assertEquals(original.owner, actual.owner)
                assertEquals(original.mode, actual.mode)
                assertEquals(original.context, actual.context)
            }
            assertTrue(repository.pending().isEmpty())
        } finally {
            root.close()
            if (createdPrefs) {
                require(prefs.canonicalFile.parentFile == prefsDirectory.canonicalFile && prefs.name == "free-xml-fixture-$token.xml")
                check(prefs.delete() || !prefs.exists())
            }
            require(fixture.canonicalFile.parentFile == context.filesDir.canonicalFile && fixture.name == "free-xml-fixture-$token")
            check(fixture.deleteRecursively() || !fixture.exists())
        }
    }
}
