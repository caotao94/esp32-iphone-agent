package com.example.esp32agent.android.gestures

import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.Pt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Gestures on an absolute pointer: the board reports X and Y in 0-32767 of the screen, so a tap lands
 * exactly where the app asked. There is no pointer state to track, no drift, no AssistiveTouch and nothing
 * to calibrate - the whole closed loop of the iPhone version's Pointer.swift is not needed here.
 *
 * Needs an Android firmware build (`hardware/flash.sh --android`, see docs/ble-protocol.md); the stock build
 * answers `err needs the android build`, which is how [probeAbsolute] tells them apart.
 */
class AbsGestures(override val device: HidDevice) : Gestures {
    companion object {
        /** The HID absolute range the firmware clamps to. */
        const val MAX = 32767

        /** How long a tap holds, and how long the pointer rests after it (a tap is not the start of a drag). */
        const val TAP_HOLD_MS = 60L
        const val TAP_GAP_MS = 30L

        /** One report every 8 ms on the board: a swipe of about a third of the screen reads as a gesture. */
        const val SWIPE_MS = 250L

        /** Rest before lifting, so lists and pickers stop where the stroke ended (no momentum). */
        const val SWIPE_HOLD_MS = 120L
    }

    var log: (String) -> Unit = { DebugLog.write(it) }

    override val ready: Boolean get() = true
    override val quality: Double? get() = null
    override val exact: Boolean get() = true

    private fun ax(x: Double) = (x.coerceIn(0.0, 1.0) * MAX).roundToInt()
    private fun ay(y: Double) = (y.coerceIn(0.0, 1.0) * MAX).roundToInt()

    override suspend fun tap(x: Double, y: Double, aim: Boolean) {
        if (aim) log("aim: an absolute pointer is already where it was asked to be")
        device.send("tap ${ax(x)} ${ay(y)} $TAP_HOLD_MS")
        delay(TAP_GAP_MS)
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, hold: Boolean) {
        val holdMs = if (hold) SWIPE_HOLD_MS else 0L
        device.send("swipe ${ax(x1)} ${ay(y1)} ${ax(x2)} ${ay(y2)} $SWIPE_MS $holdMs")
        delay(TAP_GAP_MS)
    }

    /** Press at the first point, move through the rest, release: one continuous stroke (path, circle). */
    override suspend fun drag(points: List<Pt>, hold: Boolean) {
        if (points.isEmpty()) throw DeviceError("a stroke needs at least one point")
        device.send("abs ${ax(points[0].x)} ${ay(points[0].y)}")
        delay(10)
        device.send("press")
        try {
            for (p in points.drop(1)) {
                device.send("abs ${ax(p.x)} ${ay(p.y)}")
                delay(8)
            }
            if (hold) delay(SWIPE_HOLD_MS)
        } catch (e: Exception) {
            runCatching { device.send("release") }
            throw e
        }
        device.send("release")
        delay(TAP_GAP_MS)
    }

    override suspend fun type(text: String) {
        device.send("type $text")
    }

    override suspend fun key(combo: String) {
        device.send("key $combo")
    }

    /** The board's consumer-control Home key (AC Home), which Android maps to KEYCODE_HOME. */
    override suspend fun goHome() {
        device.send("homescreen")
        delay(350)
    }

    /** AC Back, which Android maps to KEYCODE_BACK; ESC is the fallback on an older firmware. */
    override suspend fun goBack() {
        val ok = runCatching { device.send("back") }.isSuccess
        if (!ok) {
            log("the board does not know `back` (reflash with --android); sending ESC instead")
            device.send("key esc")
        }
        delay(250)
    }

    override suspend fun reset() {
        device.send("release")
    }
}