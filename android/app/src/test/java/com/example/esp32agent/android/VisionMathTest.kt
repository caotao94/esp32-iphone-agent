package com.example.esp32agent.android

import com.example.esp32agent.android.agent.VisionMath
import com.example.esp32agent.android.util.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "did the screen change" and "where is the pointer" checks, on synthetic frames. */
class VisionMathTest {
    private fun gray(w: Int, h: Int, fill: Int = 200): VisionMath.Gray =
        VisionMath.Gray(w, h, ByteArray(w * h) { fill.toByte() })

    private fun scales(coarse: VisionMath.Gray, fine: VisionMath.Gray) = VisionMath.Scales(coarse, fine)

    @Test
    fun nothingChangedIsNotAChange() {
        val a = scales(gray(32, 64), gray(100, 200))
        val b = scales(gray(32, 64), gray(100, 200))
        assertFalse(VisionMath.changed(a, b))
    }

    @Test
    fun aWholeNewScreenIsAChange() {
        val a = scales(gray(32, 64, 200), gray(100, 200, 200))
        val b = scales(gray(32, 64, 20), gray(100, 200, 20))
        assertTrue(VisionMath.changed(a, b))
    }

    @Test
    fun aSmallChangeIsStillAChange() {
        val fineA = gray(100, 200)
        val fineB = gray(100, 200)
        // a checkmark appearing: 8 dark pixels, well below the coarse threshold
        for (i in 0 until 8) fineB.px[120 * 100 + 50 + i] = 30
        assertTrue(VisionMath.changed(scales(gray(32, 64), fineA), scales(gray(32, 64), fineB)))
    }

    @Test
    fun aMissingFrameCountsAsChanged() {
        assertTrue(VisionMath.changed(null, scales(gray(32, 64), gray(10, 10))))
    }

    @Test
    fun pointerMovesWhereTheyAreIgnored() {
        val fineA = gray(100, 200)
        val fineB = gray(100, 200)
        for (i in 0 until 8) fineB.px[120 * 100 + 20 + i] = 10  // a small change at the pointer's spot
        val at = scales(gray(32, 64), fineA)
        val bt = scales(gray(32, 64), fineB)
        // masked (the pointer was there): not a change. masked somewhere else: a change.
        assertFalse(VisionMath.changed(at, bt, listOf(Pt(0.2, 0.6))))
        assertTrue(VisionMath.changed(at, bt, listOf(Pt(0.8, 0.2))))
    }

    @Test
    fun theStatusBarIsIgnored() {
        val fineA = gray(100, 200)
        val fineB = gray(100, 200)
        for (i in 0 until 300) fineB.px[i] = 10  // the very top rows: the clock
        assertFalse(VisionMath.changed(scales(gray(32, 64), fineA), scales(gray(32, 64), fineB)))
    }

    @Test
    fun findsAMovedPointer() {
        val w = 200
        val h = 400
        val before = gray(w, h)
        val after = gray(w, h)
        val dotX = 100
        val dotY = 200
        val shift = 0.025
        fun dot(g: VisionMath.Gray, cx: Int) {
            for (y in dotY until dotY + 5) for (x in cx until cx + 5) g.px[y * w + x] = 20
        }
        dot(before, dotX)
        dot(after, dotX + (shift * w).toInt())
        val found = VisionMath.pointer(before, after, Pt(0.5, 0.5), shift, Pt(0.5, 0.5))
        assertTrue("pointer not found", found != null)
        assertEquals(dotX.toDouble() / w, found!!.x, 0.02)
        assertEquals(dotY.toDouble() / h + 2.0 / h, found.y, 0.02)
    }

    @Test
    fun refusesToGuessWhenEverythingMoved() {
        val before = gray(200, 400, 200)
        val after = gray(200, 400, 40)
        assertNull(VisionMath.pointer(before, after, Pt(0.5, 0.5), 0.025, Pt(0.5, 0.5)))
    }
}