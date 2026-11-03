package com.example.esp32agent.android.agent

/**
 * Self-healing after a lost link: which part is missing (the board or the capture), when to ask the user,
 * and when to give up. Pure: the caller polls it with the link state and the time.
 * Port of Agent/Recovery.swift; the iPhone app's "hotspot" part has no Android equivalent, because the
 * phone captures its own screen and keeps its own Wi-Fi.
 */
class Recovery(private val started: Double, private val limit: Double = LIMIT) {
    /** What the app sees of the link right now. */
    data class Link(
        val connected: Boolean,   // BLE link to the board is up
        val capturing: Boolean,   // the MediaProjection session is running
        val frameAt: Double,      // when the newest captured frame arrived
    )

    enum class Part { BOARD, CAPTURE }

    data class Stage(val part: Part, val ask: Boolean)  // ask: waited long enough, the user has to do something

    sealed class Next {
        data class Wait(val stage: Stage) : Next()
        data object Live : Next()
        data class GiveUp(val reason: String) : Next()
    }

    val startedAt: Double get() = started
    var stage: Stage? = null
        private set
    private var partSince = 0.0

    /** Live once the board is back, the capture runs, and a frame newer than the loss has arrived. */
    fun next(l: Link, t: Double): Next {
        val part: Part? = when {
            !l.connected -> Part.BOARD
            !l.capturing || l.frameAt <= started -> Part.CAPTURE
            else -> null
        }
        if (part == null) {
            stage = null
            return Next.Live
        }
        if (part != stage?.part) partSince = t
        val s = Stage(part, ask = t - partSince >= (quiet[part] ?: 20.0))
        stage = s
        if (t - started >= limit) return Next.GiveUp(reason(part))
        return Next.Wait(s)
    }

    companion object {
        /** How long each part may come back by itself before the user is asked; then the whole limit. */
        val quiet: Map<Part, Double> = mapOf(Part.BOARD to 30.0, Part.CAPTURE to 12.0)
        const val LIMIT = 300.0

        fun reason(part: Part): String = when (part) {
            Part.BOARD -> "the board did not come back: plug it in again, unlock the phone, keep it nearby"
            Part.CAPTURE -> "screen capture stopped: start the run again and choose Entire screen"
        }
    }
}
