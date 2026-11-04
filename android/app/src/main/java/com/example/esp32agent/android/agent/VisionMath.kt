package com.example.esp32agent.android.agent

import com.example.esp32agent.android.util.Pt
import kotlin.math.abs
import kotlin.math.pow

/**
 * The screen-comparison math, on gray bitmaps: did the screen change after an action, and where is the
 * pointer. Pure, so it runs in plain JVM tests; [Vision] adapts Android bitmaps to it.
 * Port of Agent/Vision.swift (which ports the legacy server's vision.py).
 */
object VisionMath {
    private fun gray(value: Byte): Int = value.toInt() and 0xff
    /** Top share of the frame never compared: the status bar, where the clock and our own notification live. */
    const val topMask = 0.06
    /** Navigation indicators do not invalidate coordinates in the content area. */
    const val bottomMask = 0.04

    /** A grayscale bitmap (the "L" weights PIL uses), row-major. */
    class Gray(val w: Int, val h: Int, val px: ByteArray) {
        init { require(px.size == w * h) { "gray bitmap is ${px.size} bytes, not ${w * h}" } }
    }

    /** One frame at the two scales the change check compares: coarse for a new screen, fine for a checkmark. */
    class Scales(val coarse: Gray, val fine: Gray)

    /**
     * True when the screen content changed, including small things such as a checkmark.
     *
     * `ignore` lists screen points (fractions) where the pointer was or went: those spots are masked, so
     * pointer moves never count as a change. The status bar is ignored too.
     */
    fun changed(before: Scales?, after: Scales?, ignore: List<Pt> = emptyList(), threshold: Double = 1.0): Boolean {
        val a = before ?: return true
        val b = after ?: return true
        if (a.coarse.w != b.coarse.w || a.coarse.h != b.coarse.h) return true
        if (a.fine.w != b.fine.w || a.fine.h != b.fine.h) return true

        var total = 0
        var compared = 0
        for (i in a.coarse.px.indices) {
            if (excluded(i % a.coarse.w, i / a.coarse.w, a.coarse, ignore)) continue
            total += abs(gray(a.coarse.px[i]) - gray(b.coarse.px[i]))
            compared++
        }
        if (compared > 0 && total.toDouble() / compared > threshold) return true  // a big change: new screen, scrolled list

        val w = a.fine.w
        val h = a.fine.h
        var hits = 0
        for (i in 0 until w * h) {
            if (abs(gray(a.fine.px[i]) - gray(b.fine.px[i])) <= 25) continue
            if (excluded(i % w, i / w, a.fine, ignore)) continue
            hits++
        }
        return hits >= 6
    }

    /**
     * A substantial content change can invalidate coordinates chosen on an earlier image. Small
     * updates (cursor blink, spinner, button ripple) do not; clock and navigation pixels are excluded.
     * Both a material average difference and a broad changed area are required.
     */
    fun sceneChanged(before: Gray?, after: Gray?, ignore: List<Pt> = emptyList()): Boolean {
        val a = before ?: return true
        val b = after ?: return true
        if (a.w != b.w || a.h != b.h) return true
        var total = 0
        var hits = 0
        var compared = 0
        for (i in a.px.indices) {
            if (excluded(i % a.w, i / a.w, a, ignore, navigation = true)) continue
            val difference = abs(gray(a.px[i]) - gray(b.px[i]))
            total += difference
            if (difference > 25) hits++
            compared++
        }
        return compared > 0 && total.toDouble() / compared > 8.0 && hits.toDouble() / compared >= 0.12
    }

    private fun excluded(x: Int, y: Int, frame: Gray, ignore: List<Pt>, navigation: Boolean = false): Boolean {
        if (y <= topMask * frame.h || (navigation && y >= (1.0 - bottomMask) * frame.h)) return true
        val radius = 0.05 * frame.w
        return ignore.any {
            val dx = x - it.x * frame.w
            val dy = y - it.y * frame.h
            dx * dx + dy * dy <= radius * radius
        }
    }

    /**
     * Where the pointer is in `before`, given it moved right by `shift` (a fraction of the width) in `after`.
     *
     * Only the pointer moved between the two frames, so the changed pixels within `reach` of `near` are the
     * pointer at both spots; their centroid sits shift/2 to the right of the first one. Null if unclear.
     * Only the relative-pointer backend needs this: an absolute pointer is always where the app put it.
     */
    fun pointer(a: Gray, b: Gray, near: Pt, shift: Double, reach: Pt = Pt(0.12, 0.08)): Pt? {
        if (a.w != b.w || a.h != b.h) return null
        val w = a.w
        val h = a.h
        val left = maxOf(0, ((near.x - reach.x) * w).toInt())
        val top = maxOf((0.05 * h).toInt(), ((near.y - reach.y) * h).toInt())  // skip the status bar
        val right = minOf(w, ((near.x + reach.x) * w).toInt())
        val bottom = minOf(h, ((near.y + reach.y) * h).toInt())
        if (right <= left || bottom <= top) return null

        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in top until bottom) {
            for (x in left until right) {
                if (abs(gray(a.px[y * w + x]) - gray(b.px[y * w + x])) > 30) {
                    xs.add(x - left)
                    ys.add(y - top)
                }
            }
        }
        val n = xs.size
        val area = (right - left) * (bottom - top)
        if (n < 15 || n > area / 4) return null  // nothing moved, or more than a pointer moved
        if (xs.max() - xs.min() > 0.15 * w || ys.max() - ys.min() > 0.07 * h) return null  // not one compact spot
        val cx = xs.sum().toDouble() / n + left
        val cy = ys.sum().toDouble() / n + top
        return Pt(cx / w - shift / 2, cy / h)
    }
}
