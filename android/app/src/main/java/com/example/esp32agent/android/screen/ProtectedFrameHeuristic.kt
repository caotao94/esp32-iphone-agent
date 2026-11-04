package com.example.esp32agent.android.screen

/**
 * A FLAG_SECURE/DRM window is commonly delivered to MediaProjection as a uniform black frame. This is only
 * a guard against sending an obviously unusable frame to the model; it is deliberately conservative and needs
 * several consecutive samples before reporting a protected window.
 */
object ProtectedFrameHeuristic {
    fun likelyBlocked(luma: IntArray): Boolean {
        if (luma.size < 16) return false
        var dark = 0
        var min = 255
        var max = 0
        for (value in luma) {
            val v = value.coerceIn(0, 255)
            if (v <= 10) dark++
            min = minOf(min, v)
            max = maxOf(max, v)
        }
        return dark.toDouble() / luma.size >= 0.94 && max - min <= 25
    }
}
