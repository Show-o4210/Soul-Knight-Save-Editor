package com.example.soul_knight_save_editor.unlock

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opt in to read-only discovery; never scans/stops a game and never writes save files. */
class DiscoveryDeviceTest {
    @Test fun discoveryFindsExpectedInstalledGameAndReportsProgress() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("saveDiscovery") == "true")
        val expected = requireNotNull(args.getString("expectedDiscoveryPackage"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        RootStorage().use { storage ->
            val found = mutableListOf<GameCandidate>()
            val progress = mutableListOf<DiscoveryProgress>()
            val result = storage.discover(android.os.Process.myUid() / 100000, expected, context.packageName, { false }, progress::add, found::add)
            assertTrue("Expected game must be recognized by actual file contents", found.any { it.packageName == expected })
            assertEquals(found.size, found.map { it.packageName }.distinct().size)
            assertTrue(found.all { it.evidence.isNotEmpty() })
            assertTrue(progress.zipWithNext().all { (a, b) -> a.checked <= b.checked })
            assertTrue(result.checked > 0)
        }
    }
    @Test fun alreadyCancelledDiscoveryDoesNotOpenRoot() {
        RootStorage().use { storage ->
            assertThrows(DiscoveryCancelled::class.java) {
                storage.discover(0, "com.test.game", "com.assistant.app", { true }, { fail("No progress after cancellation") }, { fail("No candidate after cancellation") })
            }
        }
    }
}
