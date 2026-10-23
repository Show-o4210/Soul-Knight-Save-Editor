package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class GameProcessProbeTest {
    private val pkg = "com.test.game"
    private fun snapshot(vararg processes: String) = "  UID PID ARGS\n0 1 init\n" + processes.joinToString("\n")

    @Test fun mainAndChildProcessesAreDetectedWithoutRecentTaskState() {
        assertTrue(GameProcessProbe.isRunning(snapshot("10123 300 $pkg"), pkg, 0))
        assertTrue(GameProcessProbe.isRunning(snapshot("10123 301 $pkg:worker --flag"), pkg, 0))
        assertTrue(GameProcessProbe.isRunning(snapshot("99001 302 $pkg:isolated"), pkg, 0))
        assertFalse(GameProcessProbe.isRunning(snapshot("1000 10 system_server"), pkg, 0))
    }

    @Test fun theSamePackageInAnotherAndroidUserDoesNotBlockThisUser() {
        val processes = snapshot("1010123 300 $pkg", "1099001 301 $pkg:isolated")
        assertFalse(GameProcessProbe.isRunning(processes, pkg, 0))
        assertTrue(GameProcessProbe.isRunning(processes, pkg, 10))
        assertFalse(GameProcessProbe.isRunning(snapshot("10123 300 $pkg:service"), pkg, 10))
    }

    @Test fun packagePrefixesAndCommandArgumentsAreNotPackageProcesses() {
        val processes = snapshot("10123 300 $pkg.other", "10123 301 ${pkg}beta:worker",
            "0 302 sh -c echo $pkg", "0 303 app_process /system/bin $pkg")
        assertFalse(GameProcessProbe.isRunning(processes, pkg, 0))
    }

    @Test fun whitespaceAndCarriageReturnsAreAcceptedWithoutTruncatingTheName() {
        val longPackage = "com.test." + "long".repeat(30)
        val processes = "\r\n UID\tPID\tARGS \r\n 0 1 init\r\n 10123\t300\t$longPackage:worker argument\r\n"
        assertTrue(GameProcessProbe.isRunning(processes, longPackage, 0))
    }

    @Test fun incompleteOrUnsupportedProcessListsFailClosed() {
        val invalid = listOf("", "UID PID ARGS\n", "USER PID NAME\nroot 1 init\n",
            "UID PID ARGS\n10123 300 $pkg\n", snapshot("u0_a123 300 $pkg"),
            snapshot("10123 bad $pkg"), snapshot("10123 0 $pkg"), snapshot("10123 300"),
            snapshot("10123 300 $pkg", "10123 300 $pkg:duplicate"),
            snapshot("2147483648 300 $pkg"), snapshot("10123 2147483648 $pkg"),
            snapshot("10123 300 $pkg", "ps: Permission denied"))
        invalid.forEach { output ->
            assertThrows(IllegalArgumentException::class.java) { GameProcessProbe.isRunning(output, pkg, 0) }
        }
    }

    @Test fun aMatchingRowDoesNotHideAnInvalidTail() {
        assertThrows(IllegalArgumentException::class.java) {
            GameProcessProbe.isRunning(snapshot("10123 300 $pkg", "truncated"), pkg, 0)
        }
    }
}
