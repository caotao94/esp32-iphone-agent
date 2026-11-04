package com.example.esp32agent.android

import com.example.esp32agent.android.screen.ProtectedFrameHeuristic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedFrameHeuristicTest {
    @Test fun uniformBlackFrameIsBlocked() {
        assertTrue(ProtectedFrameHeuristic.likelyBlocked(IntArray(64)))
    }

    @Test fun normalFrameIsNotBlocked() {
        val frame = IntArray(64) { (it * 37) and 255 }
        assertFalse(ProtectedFrameHeuristic.likelyBlocked(frame))
    }

    @Test fun tinySampleIsIgnored() {
        assertFalse(ProtectedFrameHeuristic.likelyBlocked(IntArray(8)))
    }
}
