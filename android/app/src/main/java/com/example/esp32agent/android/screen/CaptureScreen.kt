package com.example.esp32agent.android.screen

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.now
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The eyes: a MediaProjection capture of the phone's own screen, as downscaled JPEG frames.
 *
 * This replaces the iPhone version's AirPlay path (board hotspot + H.264 over BLE at 480x1040 and <=5 fps):
 * on Android the app can read the screen itself, at a real frame rate and without giving up the Wi-Fi, so
 * the board only has to be the hands.
 *
 * Android 14+ wants a `mediaProjection` foreground service running and a fresh consent per session, so the
 * caller ([com.example.esp32agent.android.AgentService]) starts the service first, then hands the activity
 * result here. FLAG_SECURE windows (banking, DRM) are not captured, like the iPhone's unmirrored screens.
 */
class CaptureScreen(private val context: Context) : ScreenSource {
    companion object {
        /** The longest side sent to the model: enough to read a list row, small enough for a fast upload. */
        const val LONGEST_SIDE = 1024
        const val JPEG_QUALITY = 80
        private const val VIRTUAL_DISPLAY_NAME = "esp32-agent"
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val frameLock = Any()
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    // Image.timestamp uses CLOCK_MONOTONIC (the same clock as System.nanoTime()),
    // while the rest of the agent uses wall-clock seconds. Keep an offset so a
    // frame captured before an action cannot look new merely because its callback
    // was delivered after that action.
    private val wallAtMonotonicStart = now() - System.nanoTime() / 1_000_000_000.0

    @Volatile private var current: ByteArray? = null
    @Volatile private var arrived = 0.0
    @Volatile private var captured = 0.0
    @Volatile private var contentChanged = 0.0
    @Volatile private var ratio = 9.0 / 19.5
    @Volatile private var running = false
    @Volatile private var captureBlocked = false
    private var blockedFrames = 0

    @Volatile var note: String = ""
        private set

    /** Permission/display are active, even while the first image is still being delivered. */
    val isCapturing: Boolean get() = running

    override val live: Boolean
        // MediaProjection is allowed to deliver only one image for a static screen. The projection
        // callback reports revocation/teardown; frame age alone would incorrectly make a valid static
        // screen disappear while the model is thinking.
        get() = running && current != null && power.isInteractive && !keyguard.isKeyguardLocked

    override val blocked: Boolean get() = captureBlocked

    override val frame: ByteArray? get() = current
    override val updated: Double get() = arrived
    override val capturedAt: Double get() = captured
    override val changedAt: Double get() = contentChanged
    override val aspect: Double get() = ratio
    override val snapshot: ScreenSnapshot
        get() = synchronized(frameLock) { ScreenSnapshot(current, arrived, captured) }

    /** The display size to capture at: the real one, scaled down to [LONGEST_SIDE] on its long edge. */
    fun targetSize(): Pair<Int, Int> {
        val metrics = metrics()
        val scale = (LONGEST_SIDE.toDouble() / maxOf(metrics.widthPixels, metrics.heightPixels)).coerceAtMost(1.0)
        // Even sizes: some encoders and the scaler prefer them, and it keeps the aspect stable.
        val w = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val h = ((metrics.heightPixels * scale).toInt() / 2) * 2
        return maxOf(2, w) to maxOf(2, h)
    }

    private fun metrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    /**
     * Start capturing with the result of [MediaProjectionManager.createScreenCaptureIntent].
     * The foreground service must already be running (Android 14+). True when the display was created.
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        stop()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (p == null) {
            note = "the screen capture permission was not granted"
            DebugLog.write("capture: no MediaProjection from the consent result")
            return false
        }
        val handler = HandlerThread("agent-capture").also { it.start() }
        thread = handler
        // Required before getVirtualDisplay on Android 14+, and where a revoked session shows up.
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection !== p) return // A stopped older session must not tear down a newer one.
                DebugLog.write("capture: the projection stopped (the user revoked it)")
                note = "screen capture was revoked"
                teardown()
            }
        }
        projectionCallback = cb
        p.registerCallback(cb, Handler(handler.looper))

        val (w, h) = targetSize()
        val dpi = metrics().densityDpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection = p
        reader = r
        ratio = w.toDouble() / h
        running = true
        note = ""
        r.setOnImageAvailableListener({ onImage(it) }, Handler(handler.looper))
        val d = runCatching {
            p.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, Handler(handler.looper),
            )
        }.getOrNull()
        if (d == null) {
            note = "the virtual display could not be created"
            DebugLog.write("capture: createVirtualDisplay failed")
            teardown()
            return false
        }
        display = d
        DebugLog.write("capture: $w x $h at $dpi dpi")
        return true
    }

    fun stop() {
        teardown()
    }

    private fun teardown() {
        synchronized(frameLock) {
            running = false
            current = null
            captureBlocked = false
            blockedFrames = 0
            arrived = 0.0
            captured = 0.0
            contentChanged = 0.0
            lastLuma = null
        }
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
        thread?.quitSafely()
        thread = null
    }

    private fun onImage(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val plane = image.planes.firstOrNull() ?: return
            val receivedAt = now()
            val imageTimestamp = image.timestamp
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
            val luma = IntArray(64)
            var n = 0
            // Ignore the status/navigation bars; protected app content is normally black in the middle.
            for (gy in 0 until 8) {
                val y = (cropped.height * (0.12 + gy * 0.10)).toInt().coerceIn(0, cropped.height - 1)
                for (gx in 0 until 8) {
                    val x = (cropped.width * (0.06 + gx * 0.125)).toInt().coerceIn(0, cropped.width - 1)
                    val p = cropped.getPixel(x, y)
                    luma[n++] = (0.299 * ((p shr 16) and 0xff) + 0.587 * ((p shr 8) and 0xff) + 0.114 * (p and 0xff)).toInt()
                }
            }
            val protectedFrame = ProtectedFrameHeuristic.likelyBlocked(luma)
            blockedFrames = if (protectedFrame) (blockedFrames + 1).coerceAtMost(3) else 0
            captureBlocked = blockedFrames >= 3
            if (captureBlocked) {
                note = "the current app blocks screen capture (FLAG_SECURE/DRM)"
                DebugLog.write("capture: protected app content detected")
            } else if (note == "the current app blocks screen capture (FLAG_SECURE/DRM)") {
                note = ""
            }
            val out = ByteArrayOutputStream(cropped.width * cropped.height / 4 + 4096)
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            cropped.recycle()
            val jpeg = out.toByteArray()
            // ImageReader callbacks can be delayed. Use the image's monotonic
            // capture timestamp when available; falling back to callback arrival
            // keeps compatibility with devices that report zero timestamps.
            val capturedAt = if (imageTimestamp > 0L) {
                val converted = wallAtMonotonicStart + imageTimestamp / 1_000_000_000.0
                // A vendor clock mismatch should not make freshness unusable.
                if (converted <= receivedAt + 1.0 && converted >= receivedAt - 300.0) converted
                else receivedAt
            } else receivedAt
            synchronized(frameLock) {
                // Ignore an image still being encoded after its capture session was stopped/replaced.
                if (!running || reader !== this.reader) return
                val first = current == null
                arrived = receivedAt
                captured = capturedAt
                if (lumaChanged(luma)) contentChanged = receivedAt
                current = jpeg
                if (first) DebugLog.write("capture: first JPEG ${image.width} x ${image.height}, ${jpeg.size} bytes")
            }
        } catch (e: Exception) {
            note = "frame failed: ${e.message}"
            DebugLog.write("capture: ${e.message}")
        } finally {
            image?.close()
        }
    }

    private var lastLuma: IntArray? = null

    /** Ignore repeated/near-identical frames for settle timing, while recognizing a real layout update. */
    private fun lumaChanged(luma: IntArray): Boolean {
        val previous = lastLuma
        lastLuma = luma.copyOf()
        if (previous == null) return true
        var changed = 0
        for (i in luma.indices) if (abs(luma[i] - previous[i]) > 8) changed++
        return changed >= 2
    }

    /** The projection API level guard, for the diagnostics screen. */
    val requiresServiceType: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
