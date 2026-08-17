package com.insta.reelsoff.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision half of capture deletion, which is the half worth testing: two
 * different callers reach it — the service clearing earlier sessions when a new
 * one is armed, and the button clearing the lot — and between them they are the
 * only things in this app that delete a file.
 */
class CaptureFilesTest {

    @Test
    fun `a capture name yields its session stamp`() {
        assertEquals(1786998932253L, captureStampOf("capture-1786998932253-000.json"))
        assertEquals(1786998932253L, captureStampOf("capture-1786998932253-042.json"))
    }

    @Test
    fun `anything that is not a capture yields no stamp`() {
        // Measured on the device: files parked in that directory by hand, or a
        // scrubbed derivative someone left there, must be untouchable.
        for (name in listOf(
            "notes.txt",
            "capture-truc.json",
            "capture-123.json",
            "capture-123-001.txt",
            "capture--001.json",
            "prefix-capture-1-2.json",
            "capture-1-2.json.bak",
            "",
        )) {
            assertNull("$name must not parse as a capture", captureStampOf(name))
            assertFalse("$name must never be deleted", isDeletableCapture(name))
            assertFalse("$name must never be deleted", isDeletableCapture(name, before = Long.MAX_VALUE))
        }
    }

    @Test
    fun `with no bound every capture is deletable`() {
        assertTrue(isDeletableCapture("capture-1-000.json"))
        assertTrue(isDeletableCapture("capture-9999999999999-999.json"))
    }

    /**
     * The bound is strict, and that is what makes arming a capture safe to run
     * twice. A double press sends two broadcasts, the two purges land on IO in no
     * particular order, and a late purge for session 1 must still leave session 2
     * alone. Comparing stamps makes that impossible rather than unlikely.
     */
    @Test
    fun `a bound deletes strictly earlier sessions and never the current one`() {
        val current = 2_000L
        assertTrue(isDeletableCapture("capture-1999-000.json", before = current))
        assertFalse(isDeletableCapture("capture-2000-000.json", before = current))
        assertFalse(isDeletableCapture("capture-2001-000.json", before = current))
    }

    @Test
    fun `a late purge cannot eat a newer session`() {
        val session1 = 1_000L
        val session2 = 2_000L
        // purge armed for session 1 running after session 2 has begun writing
        assertFalse(isDeletableCapture("capture-2000-000.json", before = session1))
        assertTrue(isDeletableCapture("capture-1000-000.json", before = session2))
    }

    @Test
    fun `an unparseable stamp is not a capture`() {
        // Long.MAX_VALUE + 1 as text: matches the shape, overflows the parse.
        assertNull(captureStampOf("capture-9223372036854775808-000.json"))
        assertFalse(isDeletableCapture("capture-9223372036854775808-000.json"))
    }
}
