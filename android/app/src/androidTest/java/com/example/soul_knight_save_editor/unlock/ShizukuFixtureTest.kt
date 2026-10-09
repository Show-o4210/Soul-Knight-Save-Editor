package com.example.soul_knight_save_editor.unlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit opt-in. Every write targets synthetic files in this assistant's own UUID folder. */
@RunWith(AndroidJUnit4::class)
class ShizukuFixtureTest {
    @Test fun largeSyntheticFileStreamsAcrossBinderRebindsAndRestoresMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "com.example.soul_knight_save_editor")
        val targetUser = android.os.Process.myUid() / 100000
        val fixture = File(context.filesDir, "shizuku-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(fixture, "game.data")
        // Both original and replacement exceed the common 1 MiB Binder transaction capacity.
        val originalBytes = ByteArray(2 * 1024 * 1024 + 137) { ((it * 31 + it / 97) and 255).toByte() }
        val replacement = originalBytes.copyOf().also { it[0] = (it[0].toInt() xor 0x55).toByte(); it[it.lastIndex] = 73 }
        require(originalBytes.size < UnlockEngine.MAX_BYTES)
        source.writeBytes(originalBytes)
        val selected = ShizukuSaveAccess(context)
        var transactionStarted = false
        try {
            // The app's explicit authorization button must have been used before opting in.
            selected.ensureReady()
            assertEquals(AccessBackend.SHIZUKU_ROOT, selected.backend)
            val original = selected.read(source.absolutePath)
            assertArrayEquals(originalBytes, original.bytes)
            assertTrue(original.owner.isNotBlank())
            assertTrue(original.mode.isNotBlank())
            selected.close()
            val rebound = selected.read(source.absolutePath)
            assertArrayEquals(originalBytes, rebound.bytes)
            assertEquals(original.owner, rebound.owner)
            assertEquals(original.mode, rebound.mode)
            assertEquals(original.context, rebound.context)
            val allowed = setOf(source.absolutePath)
            val fixtureStorage = object : DeviceStorage {
                override val backendId: String get() = selected.backendId
                override fun assertIdleForRecovery() = selected.assertIdleForRecovery()
                // Never stop the app which hosts this instrumentation. There is no game process.
                override fun stop(packageName: String, user: Int) {
                    require(packageName == context.packageName && user == targetUser)
                }
                override fun read(path: String): SaveFile { require(path in allowed); return selected.read(path) }
                override fun replace(file: SaveFile, bytes: ByteArray) { require(file.path in allowed); selected.replace(file, bytes) }
            }
            val snapshot = SaveSnapshot(context.packageName, targetUser, null, original)
            val repository = SaveRepository(File(fixture, "journals"), fixtureStorage)
            transactionStarted = true
            val id = repository.apply(snapshot, SavePlan(mapOf(original.path to replacement), listOf("synthetic pipe test")))
            assertEquals(AccessBackend.SHIZUKU_ROOT.id, repository.backendId(id))
            val after = selected.read(source.absolutePath)
            assertArrayEquals(replacement, after.bytes)
            assertEquals(original.owner, after.owner)
            assertEquals(original.mode, after.mode)
            assertEquals(original.context, after.context)
            repository.restore(id)
            val restored = selected.read(source.absolutePath)
            assertArrayEquals(originalBytes, restored.bytes)
            assertEquals(original.owner, restored.owner)
            assertEquals(original.mode, restored.mode)
            assertEquals(original.context, restored.context)
            assertTrue(repository.pending().isEmpty())
        } finally {
            // A lost result can leave a service-side writer alive. Preserve synthetic evidence
            // instead of deleting its targets unless actual backend idle has been confirmed.
            val idle = !transactionStarted || runCatching { selected.assertIdleForRecovery(); true }.getOrDefault(false)
            selected.dispose()
            if (idle) fixture.deleteRecursively()
        }
    }
}
