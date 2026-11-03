package com.example.esp32agent.android.gestures

import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.JValue
import com.example.esp32agent.android.agent.PyJSON
import com.example.esp32agent.android.agent.Vision
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj
import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.screen.ScreenSource
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.Pt
import com.example.esp32agent.android.util.now
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Touch-like gestures from a **relative** mouse, for a board flashed with the stock (iPhone) firmware: the
 * pointer is relative, so this tracks where it is (in mouse units), moves from there, and every few gestures
 * slams it into the top-left corner (`home`) to cancel drift. Coordinates are fractions of the screen, so one
 * implementation works for any frame size.
 *
 * Port of Agent/Pointer.swift, which ports the legacy server's phone.py. The absolute-pointer backend
 * ([AbsGestures]) replaces all of this when the board is an Android build; this class is the fallback for a
 * board that is not (see [probeAbsolute]), and it is also what keeps one app working on both firmwares.
 */
class RelGestures(
    override val device: HidDevice,
    private val calibrationFile: File,
    private val screen: ScreenSource?,
) : Gestures {

    companion object {
        const val CALIBRATION_MOVE = 200          // mouse units moved diagonally for a one-point calibration
        val CALIBRATION_STOPS = listOf(150, 350)  // automatic calibration finds the pointer at these distances
        const val REHOME_EVERY = 10               // gestures between re-homing
        const val HOLD = 0.25                     // s to rest before lifting, so a swipe stops where it ends
        const val TAP_GAP = 0.15                  // s to stay still after a click, so it is a finished tap
        const val NUDGE = 12                      // mouse units the pointer is wiggled by to find it on screen
        const val AIM_TOLERANCE = 0.008           // screen fraction; a bigger miss is corrected before pressing
        const val MAX_MISS = 0.06                 // bigger misses are corrected, but not learned from
        private const val SEEN_LIMIT = 12         // landing samples kept per axis
    }

    data class Span(val w: Int, val h: Int)

    var span: Span? = null
        private set
    override var quality: Double? = null
        private set
    var aspect: Double? = null
        private set

    private var pos: Pair<Int, Int>? = null  // pointer position in mouse units; null = unknown
    private var moves = 0
    private var link = 0  // device.links when the pointer was last homed
    private val seen: MutableMap<String, ArrayDeque<Pair<Double, Double>>> =
        mutableMapOf("x" to ArrayDeque(), "y" to ArrayDeque())

    var log: (String) -> Unit = { DebugLog.write(it) }

    /** The device screen's width / height, stored with a calibration. */
    var screenAspect: () -> Double = { 9.0 / 19.5 }

    /** Last-resort pointer finder on a frame (the vision model), when the pixel diff finds nothing. */
    var locate: (suspend (ByteArray) -> Pt?)? = null

    init { load() }

    override val ready: Boolean get() = span != null
    override val exact: Boolean get() = false

    /** How many probe stops the automatic calibration makes, for a progress line. */
    val stopCount: Int get() = CALIBRATION_STOPS.size

    private fun remember(axis: String, sample: Pair<Double, Double>) {
        val q = seen.getOrPut(axis) { ArrayDeque() }
        q.addLast(sample)
        while (q.size > SEEN_LIMIT) q.removeFirst()
    }

    // MARK: - calibration

    /**
     * Find the pointer at two known distances from the corner and fit the mapping from both.
     * True when it calibrated itself; otherwise the user taps the pointer ([finishCalibration]).
     */
    suspend fun startCalibration(progress: ((Int) -> Unit)? = null): Boolean {
        pos = null
        progress?.invoke(0)
        device.send("home")
        val found = ArrayList<Triple<Int, Double, Double>>()
        var lastUnits = 0
        for ((i, units) in CALIBRATION_STOPS.withIndex()) {
            progress?.invoke(i + 1)
            device.send("move ${units - lastUnits} ${units - lastUnits}")
            lastUnits = units
            val s = screen
            if (s == null || !s.live) break
            val p = findPointer() ?: break
            found.add(Triple(units, p.x, p.y))
        }
        if (found.size < 2) {
            log("calibration: pointer not found on screen, waiting for a tap")
            if (found.isEmpty()) {
                device.send("home")
                device.send("move $CALIBRATION_MOVE $CALIBRATION_MOVE")
            }
            return false
        }
        val (u1, x1, y1) = found[0]
        val (u2, x2, y2) = found[1]
        val s = Span(
            w = ((u2 - u1).toDouble() / (x2 - x1)).roundToInt(),
            h = ((u2 - u1).toDouble() / (y2 - y1)).roundToInt(),
        )
        span = s
        quality = null
        aspect = screenAspect()
        seen["x"] = ArrayDeque()
        seen["y"] = ArrayDeque()
        for ((units, x, y) in found) {  // where each stop landed, so moves correct for the offset too
            remember("x", units.toDouble() / s.w to x)
            remember("y", units.toDouble() / s.h to y)
        }
        save()
        log("calibration: span ${s.w} x ${s.h}")
        return true
    }

    /** Wiggle the pointer and find it anywhere on screen (the span may still be unknown). */
    private suspend fun findPointer(): Pt? {
        val s = screen ?: return null
        s.settled(quiet = 0.2, least = 0.3, most = 1.5)
        val before = s.frame
        val sent = now()
        device.send("move $NUDGE 0")
        var after = s.fresh(sent, 1.0)
        if (after != null) {
            s.settled(quiet = 0.15, least = 0.1, most = 0.6)
            after = s.frame
        }
        device.send("move -$NUDGE 0")
        if (before == null || after == null) return null
        val pixel = Vision.pointer(before, after, Pt(0.5, 0.5), 0.025, Pt(0.5, 0.5))
        val found = pixel ?: locate?.invoke(after)
        if (found == null) return null
        if (pixel == null) log("calibration: the vision model found the pointer at ${found.x}, ${found.y}")
        return if (found.x in 0.03..0.97 && found.y in 0.03..0.97) found else null
    }

    /** x, y: where the pointer ended up after [startCalibration] (one point, picked by the user). */
    fun finishCalibration(x: Double, y: Double) {
        quality = null
        aspect = screenAspect()
        span = Span(
            w = (CALIBRATION_MOVE / x).roundToInt(),
            h = (CALIBRATION_MOVE / y).roundToInt(),
        )
        seen["x"] = ArrayDeque()
        seen["y"] = ArrayDeque()
        save()
    }

    /** A measured check (e.g. a probe tap) as the current accuracy. */
    fun setQuality(q: Double) {
        quality = q
        save()
    }

    /** Calibration plus the observed landings, so the correction survives a restart. */
    private fun save() {
        val s = span ?: return
        fun samples(axis: String) = JValue.ArrV((seen[axis] ?: ArrayDeque()).map { listOf(it.first.j(), it.second.j()).j() })
        val obj = jObj(
            "w" to s.w.j(), "h" to s.h.j(),
            "seen" to jObj("x" to samples("x"), "y" to samples("y")).j(),
            "quality" to (quality?.j() ?: JValue.NullV),
            "aspect" to (aspect?.j() ?: JValue.NullV),
        )
        runCatching { calibrationFile.writeText(PyJSON.dumps(obj)) }
            .onFailure { log("calibration not saved: ${it.message}") }
    }

    private fun load() {
        val text = runCatching { calibrationFile.readText() }.getOrNull() ?: return
        val o = runCatching { JsonParse.parseObject(text) }.getOrNull() ?: return
        val w = o["w"]?.int?.toInt() ?: return
        val h = o["h"]?.int?.toInt() ?: return
        span = Span(w, h)
        quality = o["quality"]?.double
        aspect = o["aspect"]?.double
        for (axis in listOf("x", "y")) {
            val q = ArrayDeque<Pair<Double, Double>>()
            (o["seen"]?.obj?.get(axis)?.arr ?: emptyList()).forEach { p ->
                val pair = p.arr ?: return@forEach
                val a = pair.getOrNull(0)?.double ?: return@forEach
                val b = pair.getOrNull(1)?.double ?: return@forEach
                q.addLast(a to b)
            }
            while (q.size > SEEN_LIMIT) q.removeFirst()
            seen[axis] = q
        }
        log("calibration loaded: span $w x $h")
    }

    // MARK: - aiming

    suspend fun point(x: Double, y: Double, aim: Boolean = false) {
        if (!ready) throw DeviceError("pointer not calibrated")
        moveTo(dx(x), dy(y))
        if (aim && screen != null) aimAt(x, y)
    }

    /** Closed loop: find the real pointer on screen, fix a miss, and learn the error for later moves. */
    private suspend fun aimAt(x: Double, y: Double) {
        val s = span ?: return
        val found = measure(x, y, model = false)
        if (found == null) {
            log("aim ($x, $y): pointer not found")
            return
        }
        if (maxOf(abs(found.x - x), abs(found.y - y)) <= MAX_MISS) {  // learn from drift only, never a stray reading
            remember("x", fix(x, "x") to found.x)
            remember("y", fix(y, "y") to found.y)
            save()
        }
        if (abs(found.x - x) <= AIM_TOLERANCE && abs(found.y - y) <= AIM_TOLERANCE) return
        val mx = ((x - found.x) * s.w).roundToInt()
        val my = ((y - found.y) * s.h).roundToInt()
        device.send("move $mx $my")  // fix this miss right away
        pos?.let { pos = Pair(it.first + mx, it.second + my) }
    }

    /**
     * Wiggle the pointer and find it on screen, near the target first, then anywhere. model: ask the vision
     * model when the pixels do not show it (slow, rarely right); a tap's aim never waits for that.
     */
    private suspend fun measure(x: Double, y: Double, model: Boolean = true): Pt? {
        val s = screen ?: return null
        val sp = span ?: return null
        val nudge = if (x < 0.9) NUDGE else -NUDGE  // stay clear of the screen edge
        s.settled(quiet = 0.15, least = 0.1, most = 0.6)  // pointer at rest
        val before = s.frame
        val sent = now()
        device.send("move $nudge 0")
        var after = s.fresh(sent, 1.2)
        if (after != null) {
            s.settled(quiet = 0.1, least = 0.05, most = 0.4)
            after = s.frame
        }
        device.send("move -$nudge 0")
        val shift = nudge.toDouble() / sp.w
        if (before == null || after == null) return null
        Vision.pointer(before, after, Pt(x, y), shift)?.let { return it }
        Vision.pointer(before, after, Pt(0.5, 0.5), shift, Pt(0.5, 0.5))?.let { return it }
        return if (model) locate?.invoke(after) else null
    }

    /** Calibration check without tapping: move to a known point and measure the miss. Null if not found. */
    suspend fun verify(x: Double = 0.5, y: Double = 0.62): Double? {
        point(x, y)
        val found = measure(x, y) ?: return null
        val error = maxOf(abs(found.x - x), abs(found.y - y))
        setQuality(error)
        return error
    }

    /** How far the learned correction moved targets away from the plain calibration (screen fraction). */
    val drift: Double
        get() = listOf(0.2, 0.8).flatMap { t -> listOf("x", "y").map { abs(fix(t, it) - t) } }.maxOrNull() ?: 0.0

    /** The fraction to aim at so the pointer lands on t, from the line through the observed landings. */
    fun fix(t: Double, axis: String): Double {
        val pts = seen[axis] ?: return t
        if (pts.size < 2) return t
        val n = pts.size.toDouble()
        val mc = pts.sumOf { it.first } / n
        val mf = pts.sumOf { it.second } / n
        val variance = pts.sumOf { (it.first - mc).pow(2) }
        var a = if (variance > 1e-4) pts.sumOf { (it.first - mc) * (it.second - mf) } / variance else 1.0
        if (a <= 0.85 || a >= 1.15) a = 1.0  // a wild fit is worse than none
        val b = mf - a * mc
        return if (abs(b) < 0.1) (t - b) / a else t
    }

    private suspend fun moveTo(tx: Int, ty: Int, rehome: Boolean = true) {
        if (link != device.links) {  // new link: a dropped gesture may have left the button down
            device.send("release")
            pos = null
            link = device.links
        }
        if (pos == null || (rehome && moves >= REHOME_EVERY)) {
            device.send("home")
            pos = Pair(0, 0)
            moves = 0
        }
        val (x, y) = pos!!
        pos = null  // unknown until the move succeeds
        device.send("move ${tx - x} ${ty - y}")
        pos = Pair(tx, ty)
        moves += 1
    }

    // MARK: - Gestures

    override suspend fun reset() {
        pos = null
        device.send("release")
        device.send("home")
        pos = Pair(0, 0)
        moves = 0
    }

    override suspend fun tap(x: Double, y: Double, aim: Boolean) {
        point(x, y, aim)
        device.send("click")
        delay((TAP_GAP * 1000).toLong())
    }

    override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, hold: Boolean) {
        drag(listOf(Pt(x1, y1), Pt(x2, y2)), hold)
    }

    /** Press at the first point, move through the rest, release; hold rests first, so nothing overshoots. */
    override suspend fun drag(points: List<Pt>, hold: Boolean) {
        if (points.isEmpty()) throw DeviceError("a stroke needs at least one point")
        point(points[0].x, points[0].y)
        device.send("press")
        try {
            for (p in points.drop(1)) moveTo(dx(p.x), dy(p.y), rehome = false)  // never home mid-drag
            if (hold) delay((HOLD * 1000).toLong())
        } catch (e: Exception) {
            runCatching { device.send("release") }
            throw e
        }
        device.send("release")
    }

    suspend fun scroll(x: Double, y: Double, lines: Int) {
        point(x, y)
        device.send("scroll $lines 0")
    }

    override suspend fun type(text: String) {
        device.send("type $text")
    }

    override suspend fun key(combo: String) {
        device.send("key $combo")
    }

    /** The board's consumer-control Home key, which Android maps to KEYCODE_HOME. */
    override suspend fun goHome() {
        if (runCatching { device.send("homescreen") }.isSuccess) {
            delay(350)
            return
        }
        // Older firmware without the consumer key: flick up from the bottom edge (gesture navigation).
        swipe(0.5, 0.95, 0.5, 0.45, hold = false)
        pos = null  // starting at the screen's edge may have clamped the pointer: re-home before the next move
    }

    /** AC Back on any current firmware; a swipe in from the left edge otherwise (gesture navigation). */
    override suspend fun goBack() {
        if (runCatching { device.send("back") }.isSuccess) {
            delay(250)
            return
        }
        swipe(0.005, 0.45, 0.6, 0.45, hold = false)
        pos = null
    }

    private fun dx(x: Double): Int {
        val s = span ?: throw DeviceError("pointer not calibrated")
        return (fix(x, "x").coerceIn(0.0, 1.0) * s.w).roundToInt()
    }

    private fun dy(y: Double): Int {
        val s = span ?: throw DeviceError("pointer not calibrated")
        return (fix(y, "y").coerceIn(0.0, 1.0) * s.h).roundToInt()
    }
}
