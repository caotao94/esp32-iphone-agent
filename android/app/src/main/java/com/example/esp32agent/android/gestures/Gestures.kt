package com.example.esp32agent.android.gestures

import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.util.Pt

/**
 * What AgentLoop does on the phone. Port of the Gestures protocol in Agent/AgentLoop.swift, so the loop is
 * the same whichever pointer the board offers:
 *  - [AbsGestures]: an Android build (`flash.sh --android`), absolute coordinates, nothing to calibrate;
 *  - [RelGestures]: the stock iPhone build, a relative mouse, calibrated from the screen (Pointer.swift).
 * Coordinates are fractions of the screen (0..1, origin top-left).
 */
interface Gestures {
    val device: HidDevice

    /** False until a relative pointer has been calibrated; an absolute one is always ready. */
    val ready: Boolean

    /** The pointer error at the last check, as a fraction of the screen (null = not measured). */
    val quality: Double?

    /** True when gestures land exactly where they were asked (an absolute pointer). */
    val exact: Boolean

    suspend fun tap(x: Double, y: Double, aim: Boolean = false)
    suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, hold: Boolean = true)
    suspend fun drag(points: List<Pt>, hold: Boolean = true)
    suspend fun type(text: String)
    suspend fun key(combo: String)
    suspend fun goHome()
    suspend fun goBack()

    /** Let go of the button and put the pointer back into a known state, whatever a gesture left. */
    suspend fun reset()

    /** Clear text the last `type` left in the clipboard (a pasted password), once it has been entered. */
    fun forgetText() {}
}

/** Which pointer the board on the other end of the link has: `abs` answers ok only in an Android build. */
suspend fun probeAbsolute(device: HidDevice): Boolean {
    return try {
        device.send("abs 16384 16384")
        true
    } catch (e: DeviceError) {
        // The stock firmware answers one of these explicit messages. Other device
        // failures (and BoardTimeout, which is deliberately not caught here) have
        // an unknown outcome and must be surfaced instead of starting calibration.
        val message = e.message.orEmpty()
        if (message.contains("needs the android build", ignoreCase = true) ||
            message.contains("unknown command", ignoreCase = true)
        ) false else throw e
    }
}
