package com.example.esp32agent.android.agent

/**
 * Human words for a recorded step, for the run list, the notification and the replay.
 * Port of Agent/StepText.swift, with SF Symbols replaced by an emoji (no icon font in the skeleton yet).
 */
object StepText {
    fun title(s: JObject): String {
        fun n(k: String) = s[k]?.int ?: (s[k]?.double ?: 0.0).toLong()
        return when (s["action"]?.str) {
            "plan" -> "Made a plan"
            "replan (stuck)", "replan" -> "Made a new plan"
            "tap" -> "Tap at ${n("x")}, ${n("y")}"
            "swipe" -> "Swipe"
            "path" -> "Draw a path"
            "circle" -> "Draw a circle"
            "type" -> "Type “${s["text"]?.str ?: ""}”"
            "key" -> "Press ${s["combo"]?.str ?: ""}"
            "home" -> "Go to the Home Screen"
            "back" -> "Go back"
            "wait" -> "Wait"
            "done" -> "Done"
            "fail" -> "Gave up"
            "stopped" -> "Stopped"
            "hiccup" -> "Hiccup, retrying"
            "reconnected" -> "Reconnected"
            "error" -> "Error"
            "help" -> "Asked for your help"
            "takeover" -> "You took over"
            "skipped" -> "Skipped"
            "note" -> "Note"
            else -> s["action"]?.str ?: ""
        }
    }

    /** The model's own words if it gave any, else the action. */
    fun line(s: JObject): String {
        val t = s["thought"]?.str
        if (!t.isNullOrEmpty()) return t
        val a = s["answer"]?.str ?: s["reason"]?.str
        if (!a.isNullOrEmpty()) return title(s) + ": " + a
        return title(s)
    }

    fun symbol(name: String?): String = when (name) {
        "plan", "replan (stuck)" -> "📋"
        "tap" -> "👆"
        "swipe", "path", "circle" -> "✍️"
        "type" -> "⌨️"
        "key" -> "🎛️"
        "home" -> "🏠"
        "back" -> "↩️"
        "wait" -> "⏳"
        "done" -> "✅"
        "stopped" -> "⏹️"
        "help", "takeover" -> "🙋"
        "skipped" -> "⏭️"
        "note" -> "💬"
        "reconnected" -> "🔁"
        else -> "⚠️"
    }

    /** Steps that are not gestures: nothing was drawn on the screen for them. */
    val notMoves: Set<String> = setOf(
        "plan", "replan (stuck)", "hiccup", "stopped", "error", "fail", "done", "help",
        "takeover", "skipped", "note", "reconnected",
    )
}

/** How a run ended. */
enum class Outcome {
    RUNNING, DONE, FAILED, STOPPED;

    companion object {
        fun of(last: String?, running: Boolean): Outcome = when {
            running -> RUNNING
            last == "done" -> DONE
            last == "stopped" -> STOPPED
            else -> FAILED
        }
    }
}