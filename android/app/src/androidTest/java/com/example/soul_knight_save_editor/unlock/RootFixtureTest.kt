package com.example.soul_knight_save_editor.unlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in: only synthetic files inside this assistant's own sandbox, never the game package. */
@RunWith(AndroidJUnit4::class)
class RootFixtureTest {
    @Test fun rootWriteVerifyAndRestoreInOwnSandbox() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.example.soul_knight_save_editor")
        val token = UUID.randomUUID().toString()
        val fixture = File(context.filesDir, "root-fixture-$token").apply { check(mkdir()) }
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
        val prefs = File(prefsDir, "unlock-fixture-$token.xml")
        val game = File(fixture, "game.data")
        val backups = File(fixture, "journals")
        val root = RootStorage()
        try {
            val gameBytes = UnlockEngine.xor("""{"heroUnlock":{"FutureHero":false},"skinLock":{"FutureHero":[{"Key":7,"Value":-999}]},"wins":19}""".toByteArray())
            val prefsBytes = """<map><string name="42_c0_unlock">false</string><int name="42_c0_skin7" value="-999"/><int name="OpenRijTest_42" value="1"/><int name="OpenNewtonJsonTest_42" value="1"/></map>""".toByteArray()
            prefs.writeBytes(prefsBytes); game.writeBytes(gameBytes)
            val snapshot = SaveSnapshot(context.packageName, android.os.Process.myUid() / 100000, root.read(prefs.absolutePath), root.read(game.absolutePath))
            val fixtureStorage = object : DeviceStorage {
                override fun stop(packageName: String, user: Int) { require(packageName == context.packageName) /* Never force-stop our instrumentation process. */ }
                override fun read(path: String): SaveFile { require(path == prefs.absolutePath || path == game.absolutePath); return root.read(path) }
                override fun replace(file: SaveFile, bytes: ByteArray) { require(file.path == prefs.absolutePath || file.path == game.absolutePath); root.replace(file, bytes) }
            }
            val repository = SaveRepository(backups, fixtureStorage)
            val patch = UnlockEngine.unlock(gameBytes, prefsBytes, "42", UnlockSelection(setOf(0), setOf(SkinId(0, 7))))
            val id = repository.apply(snapshot, patch)
            assertEquals(true, UnlockEngine.catalog(root.read(game.absolutePath).bytes, root.read(prefs.absolutePath).bytes).heroes.single().unlocked)
            assertEquals(1, UnlockEngine.catalog(root.read(game.absolutePath).bytes, root.read(prefs.absolutePath).bytes).heroes.single().skins[7])
            repository.restore(id)
            assertArrayEquals(gameBytes, root.read(game.absolutePath).bytes)
            assertArrayEquals(prefsBytes, root.read(prefs.absolutePath).bytes)
            assertEquals(snapshot.prefs.owner, root.read(prefs.absolutePath).owner)
            assertEquals(snapshot.prefs.mode, root.read(prefs.absolutePath).mode)
            assertEquals(snapshot.prefs.context, root.read(prefs.absolutePath).context)
            assertTrue(repository.pending().isEmpty())
        } finally {
            root.close()
            // These exact UUID-named files were created by this test, not user saves.
            prefs.delete()
            fixture.deleteRecursively()
        }
    }
}
