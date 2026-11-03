package com.example.esp32agent.android.screen

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
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.now
import java.io.ByteArrayOutputStream

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

    @Volatile private var current: ByteArray? = null
    @Volatile private var arrived = 0.0
    @Volatile private var captured = 0.0
    @Volatile private var ratio = 9.0 / 19.5
    @Volatile private var running = false

    var note: String = ""
        private set

    override val live: Boolean
        get() = running && current != null && now() - arrived < 3.0

    override val frame: ByteArray? get() = current
    override val updated: Double get() = arrived
    override val capturedAt: Double get() = captured
    override val aspect: Double get() = ratio

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
            runCatching { r.close() }
            runCatching { p.stop() }
            handler.quitSafely()
            thread = null
            return false
        }
        projection = p
        display = d
        reader = r
        ratio = w.toDouble() / h
        running = true
        note = ""
        DebugLog.write("capture: $w x $h at $dpi dpi")
        return true
    }

    fun stop() {
        teardown()
    }

    private fun teardown() {
        running = false
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
            val out = ByteArrayOutputStream(cropped.width * cropped.height / 4 + 4096)
            cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            cropped.recycle()
            val jpeg = out.toByteArray()
            // The capture clock is not exposed; the frame is "captured" when it lands here, so `lag` is 0.
            current = jpeg
            arrived = now()
            captured = arrived
        } catch (e: Exception) {
            note = "frame failed: ${e.message}"
            DebugLog.write("capture: ${e.message}")
        } finally {
            image?.close()
        }
    }

    /** The projection API level guard, for the diagnostics screen. */
    val requiresServiceType: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}