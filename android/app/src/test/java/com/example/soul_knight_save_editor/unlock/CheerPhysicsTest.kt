package com.example.soul_knight_save_editor.unlock

import org.junit.Assert.*
import org.junit.Test

class CheerPhysicsTest {
    @Test fun sevenTapsHintsTimeoutAndThreeTapReplayMatchReference() {
        val trigger = CheerTrigger(false)
        for (i in 0..5) {
            val event = trigger.tap(i * 100L)
            assertFalse(event.triggered)
            if (i >= 3) assertNotNull(event.hint)
        }
        assertEquals(CheerTap(true, true), trigger.tap(600))
        assertFalse(trigger.tap(700).triggered)
        assertFalse(trigger.tap(800).triggered)
        assertEquals(CheerTap(true, false), trigger.tap(900))
        val returning = CheerTrigger(true)
        repeat(6) { assertFalse(returning.tap(it * 100L).triggered) }
        assertEquals(CheerTap(true, false), returning.tap(600))
        val timeout = CheerTrigger(false)
        repeat(6) { timeout.tap(it * 100L) }
        assertFalse(timeout.tap(1801).triggered)
        repeat(5) { assertFalse(timeout.tap(1901 + it * 100L).triggered) }
        assertTrue(timeout.tap(2401).triggered)
    }
    @Test fun fixedStepMatchesAcrossFrameRatesAndFormsInsideSmallScreens() {
        val slow = CheerPhysics(220.0, 400.0)
        val fast = CheerPhysics(220.0, 400.0)
        repeat(120) { slow.advance(1.0 / 30) }
        repeat(480) { fast.advance(1.0 / 120) }
        slow.bodies.zip(fast.bodies).forEach { (a, b) -> assertEquals(a.x, b.x, 1e-8); assertEquals(a.y, b.y, 1e-8) }
        repeat(120) { slow.advance(1.0 / 30) }
        assertTrue(slow.done)
        slow.bodies.forEachIndexed { i, body ->
            assertEquals(slow.targetX(i), body.x, 0.0)
            assertEquals(400 * .46, body.y, 0.0)
            assertTrue(body.x > 0 && body.x < 220)
            assertEquals(0.0, body.angle, 0.0)
        }
    }
    @Test fun reducedMotionShowsSignatureAndLongPauseDoesNotFastForward() {
        val still = CheerPhysics(390.0, 800.0, reducedMotion = true)
        assertTrue(still.formed)
        still.advance(60.0)
        assertTrue(still.elapsed <= .1 + 1e-9)
        assertFalse(still.done)
        assertEquals(still.targetX(0), still.bodies.first().x, 0.0)
        assertThrows(IllegalArgumentException::class.java) { still.advance(Double.NaN) }
    }
}
