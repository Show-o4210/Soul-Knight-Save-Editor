package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class NativeWriteGuardTest {
    @Test fun uncertainWriteSurvivesNewAppProcessUntilDeviceReboot() {
        var stored = -1
        var boot = 7
        val guard = NativeWriteGuard({ boot }, { stored }) { stored = it ?: -1 }
        assertThrows(AccessFailure::class.java) { guard.replace {
            throw AccessFailure(AccessFailureKind.TIMEOUT, "timeout", uncertainWrite = true)
        } }
        assertEquals(7, stored)
        val restarted = NativeWriteGuard({ boot }, { stored }) { stored = it ?: -1 }
        assertThrows(AccessFailure::class.java) { restarted.confirmIdle { fail("Cannot infer old shell completion") } }
        boot = 8
        restarted.confirmIdle { }
        assertEquals(-1, stored)
    }

    @Test fun currentProcessNeedsActualExecutorAcknowledgementBeforeClearingGuard() {
        var stored = -1
        val guard = NativeWriteGuard({ 7 }, { stored }) { stored = it ?: -1 }
        assertThrows(AccessFailure::class.java) { guard.replace {
            throw AccessFailure(AccessFailureKind.SESSION_DISCONNECTED, "disconnected", uncertainWrite = true)
        } }
        assertThrows(AccessFailure::class.java) { guard.confirmIdle {
            throw AccessFailure(AccessFailureKind.WRITE_NOT_IDLE, "still running", uncertainWrite = true)
        } }
        assertEquals(7, stored)
        guard.confirmIdle { }
        assertEquals(-1, stored)
    }

    @Test fun processDeathPreservesGuardAndConfirmedCompletionClearsIt() {
        var stored = -1
        val guard = NativeWriteGuard({ 7 }, { stored }) { stored = it ?: -1 }
        assertThrows(Error::class.java) { guard.replace { throw Error("process death") } }
        assertEquals(7, stored)
        guard.confirmIdle { }
        guard.replace { }
        assertEquals(-1, stored)
    }
}
