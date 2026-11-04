package com.example.esp32agent.android.screen

import com.example.esp32agent.android.util.now
import kotlinx.coroutines.delay

/** One image and the times belonging to that exact image. */
data class ScreenSnapshot(val frame: ByteArray?, val updated: Double, val capturedAt: Double)

/**
 * The phone's screen as JPEG frames. The iPhone app's implementation decodes AirPlay video forwarded over
 * BLE; this app's captures the screen itself ([CaptureScreen]). Same contract, so AgentLoop is unchanged.
 * Port of the ScreenSource protocol in Video/VideoFeed.swift.
 */
interface ScreenSource {
    /** True when recent frames look like a protected window rather than usable screen content. */
    val blocked: Boolean get() = false

    /** The capture is active and has a usable image; static screens need not produce new frames. */
    val live: Boolean

    /** The newest frame (JPEG), or null before the first one. */
    val frame: ByteArray?

    /** When [frame] arrived here. */
    val updated: Double

    /** When [frame] was captured (0 when unknown). */
    val capturedAt: Double

    /** When meaningful screen content last changed; duplicate capture callbacks do not move this clock. */
    val changedAt: Double get() = updated

    /** Read one image with its timestamps; capture implementations publish these atomically. */
    val snapshot: ScreenSnapshot get() = ScreenSnapshot(frame, updated, capturedAt)

    /** Frame width / height, for circles and the calibration's aspect check. */
    val aspect: Double

    /** How late the shown frame arrived after it was captured (0 when unknown), capped at a second. */
    val lag: Double get() = if (capturedAt > 0) (updated - capturedAt).coerceIn(0.0, 1.0) else 0.0

    /**
     * Wait until screen content stops changing. Repeated callbacks with the same content do not extend
     * the wait; quiet time also covers the last frame's transport lag.
     */
    suspend fun settled(quiet: Double = 0.5, least: Double = 0.3, most: Double = 4.0) {
        val started = now()
        delay((least * 1000).toLong())
        while (now() - changedAt < quiet + lag && now() - started < most) delay(100)
    }

    /** The first frame captured after `since` (received, when capture times are unknown), or null. */
    suspend fun fresh(since: Double, timeout: Double = 0.8): ByteArray? {
        val deadline = now() + timeout
        while (true) {
            val latest = snapshot
            if ((if (latest.capturedAt > 0) latest.capturedAt else latest.updated) > since) return latest.frame
            if (now() > deadline) return null
            delay(20)
        }
    }

    /** After a gesture that ended at `since`: wait until the screen shows its result. */
    suspend fun afterAction(since: Double) {
        fresh(since, 1.5)
        settled()
    }
}
