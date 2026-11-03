package com.example.esp32agent.android

import com.example.esp32agent.android.agent.Recovery
import com.example.esp32agent.android.util.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staged recovery the run waits on: wait, ask the user, give up. */
class RecoveryTest {
    private fun link(connected: Boolean = true, capturing: Boolean = true, frameAt: Double = 1e12) =
        Recovery.Link(connected, capturing, frameAt)

    @Test
    fun liveWhenEverythingIsBack() {
        val r = Recovery(100.0)
        assertTrue(r.next(link(), 101.0) is Recovery.Next.Live)
        assertNull(r.stage)
    }

    @Test
    fun waitsQuietlyThenAsks() {
        val t = now()
        val r = Recovery(t)
        val first = r.next(link(connected = false), t + 1)
        assertTrue(first is Recovery.Next.Wait)
        assertEquals(false, (first as Recovery.Next.Wait).stage.ask)
        assertEquals(Recovery.Part.BOARD, first.stage.part)
        val later = r.next(link(connected = false), t + 40)
        assertTrue(later is Recovery.Next.Wait && later.stage.ask)
    }

    @Test
    fun aStaleFrameIsTheCapture() {
        val t = 1000.0
        val r = Recovery(t)
        val next = r.next(link(capturing = true, frameAt = t - 5), t + 1)
        assertTrue(next is Recovery.Next.Wait)
        assertEquals(Recovery.Part.CAPTURE, (next as Recovery.Next.Wait).stage.part)
    }

    @Test
    fun givesUpAtTheLimit() {
        val t = 1000.0
        val r = Recovery(t, limit = 60.0)
        val next = r.next(link(connected = false), t + 61)
        assertTrue(next is Recovery.Next.GiveUp)
        assertTrue((next as Recovery.Next.GiveUp).reason.contains("board"))
    }
}