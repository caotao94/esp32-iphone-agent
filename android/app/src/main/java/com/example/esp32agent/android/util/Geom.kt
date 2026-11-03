package com.example.esp32agent.android.util

import kotlin.math.cos
import kotlin.math.sin

/**
 * A point on the screen in fractions (0..1, origin top-left), the coordinate space every gesture and every
 * pointer correction uses, so one implementation works for any frame size. Stands in for the iPhone app's
 * CGPoint.
 */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

/** Points around a circle; r is a fraction of the width, aspect = width / height. Port of circle(). */
fun circlePoints(x: Double, y: Double, r: Double, aspect: Double, n: Int = 24): List<Pt> =
    (0..n).map { i ->
        val a = 2 * Math.PI * i / n
        Pt(x + r * cos(a), y + r * aspect * sin(a))
    }