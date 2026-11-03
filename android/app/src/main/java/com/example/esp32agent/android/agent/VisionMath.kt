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
    /** Top share of the frame never compared: the status bar, where the clock and our own notification live. */
    const val topMask = 0.06

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
        for (i in a.coarse.px.indices) total += abs(a.coarse.px[i].toInt() - b.coarse.px[i].toInt())
        if (total.toDouble() / a.coarse.px.size > threshold) return true  // a big change: new screen, scrolled list

        val w = a.fine.w
        val h = a.fine.h
        val masks = ignore.map { Pt(it.x * w, it.y * h) }
        val r2 = (0.05 * w).pow(2)
        var hits = 0
        for (i in 0 until w * h) {
            if (abs(a.fine.px[i].toInt() - b.fine.px[i].toInt()) <= 25) continue
            val x = (i % w).toDouble()
            val y = (i / w).toDouble()
            if (y <= topMask * h) continue
            if (masks.any { (x - it.x).pow(2) + (y - it.y).pow(2) <= r2 }) continue
            hits++
        }
        return hits >= 6
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
                if (abs(a.px[y * w + x].toInt() - b.px[y * w + x].toInt()) > 30) {
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