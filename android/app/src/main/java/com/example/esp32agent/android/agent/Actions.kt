package com.example.esp32agent.android.agent

import com.example.esp32agent.android.util.Pt
import com.example.esp32agent.android.util.circlePoints
import kotlin.math.abs

/**
 * Parsing of the model's replies: one DO line at a time, then a whole THINK / DO... reply, then a plan.
 * Port of Agent/Actions.swift, which is itself a port of the legacy server's agent.py, so the three
 * implementations of the agent accept the same grammar (prompts/actor.md documents it for the model).
 */
object Actions {
    class ParseError(message: String) : RuntimeException(message)

    /** Bookkeeping keys that are not part of the action itself. */
    val meta: Set<String> = setOf("t", "thought", "thought_zh", "think_s", "act_s", "shot", "_shot", "changed")

    /** action name -> argument names; numeric arguments are 0-1000 screen coordinates. */
    val actions: Map<String, List<String>> = mapOf(
        "tap" to listOf("x", "y"),
        "swipe" to listOf("x1", "y1", "x2", "y2"),
        "circle" to listOf("x", "y", "r"),
        "path" to listOf("points"),
        "type" to listOf("text"),
        "key" to listOf("combo"),
        "done" to listOf("answer"),
        "fail" to listOf("reason"),
        "home" to emptyList(),
        "back" to emptyList(),
        "wait" to emptyList(),
        "replan" to emptyList(),
    )
    val textArgs: Set<String> = setOf("text", "combo", "answer", "reason")

    private val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
    private val THINK = Regex("""^\s*THINK\s*[:：]\s*(.*?)\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val ZH = Regex("""^\s*ZH\s*[:：]\s*(.*?)\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val DO = Regex("""^\s*DO\s*[:：]\s*(.*?)\s*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val PLAN = Regex("""EN\s*[:：](.*?)ZH\s*[:：](.*)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** One DO line, e.g. "tap 152 362" -> {"action": "tap", "x": 152, "y": 362}. */
    fun parseCommand(line: String): JObject {
        val trimmed = line.trim()
        val sp = trimmed.indexOf(' ')
        val head = if (sp < 0) trimmed else trimmed.substring(0, sp)
        val rest = if (sp < 0) "" else trimmed.substring(sp + 1)
        val name = head.lowercase()
        val keys = actions[name] ?: throw ParseError("unknown action: " + trimmed.take(100))
        val first = keys.firstOrNull()
        if (first != null && textArgs.contains(first)) {
            val text = rest.trim().trim('"')
            return jObj("action" to name.j(), first to text.j())
        }
        val numbers = NUMBER.findAll(rest).map { it.value.toDouble().toLong() }.toList()
        if (numbers.any { it !in 0L..1000L }) throw ParseError("$name coordinates must be within the screenshot's 0-1000 range")
        if (keys == listOf("points")) {
            if (numbers.size < 4 || numbers.size % 2 != 0) {
                throw ParseError("path needs 2+ x y pairs: " + rest)
            }
            // at most 20 points, as the action list in prompts/actor.md promises the model
            val pts = (0 until numbers.size step 2).take(20)
                .map { listOf<JValue>(numbers[it].j(), numbers[it + 1].j()).j() }
            return jObj("action" to name.j(), "points" to JValue.ArrV(pts))
        }
        if (numbers.size != keys.size) throw ParseError(name + " needs " + keys.size + " numbers: " + rest)
        val action = jObj("action" to name.j())
        keys.forEachIndexed { i, k -> action[k] = numbers[i].j() }
        if (name == "tap" && rest.lowercase().contains("precise")) action["precise"] = true.j()
        return action
    }

    /** Parse a THINK / DO... reply (an optional ZH line is kept); the thought goes on the first action. */
    fun parseActions(text: String): List<JObject> {
        val thought = group(THINK, text)
        val zh = group(ZH, text)
        val acts = DO.findAll(text).map { parseCommand(it.groupValues[1]) }.take(6).toMutableList()
        if (acts.isEmpty()) throw ParseError("no DO line in reply: " + text.take(200))
        acts[0] = jObj("thought" to (thought ?: "").j(), "thought_zh" to (zh ?: "").j()).merging(acts[0])
        return acts
    }

    /** Split an optional EN: / ZH: plan reply; a plain reply is used for both. */
    fun parsePlan(text: String): Pair<String, String> {
        val m = PLAN.find(text) ?: return text.trim() to text.trim()
        return m.groupValues[1].trim() to m.groupValues[2].trim()
    }

    /** The action itself, without thoughts, timings and other bookkeeping. */
    fun key(step: JObject): JObject {
        val name = step["action"]?.str ?: return step.without(meta)
        val parameters = actions[name] ?: return step.without(meta)
        return JObject(listOf("action" to name.j()) + parameters.mapNotNull { p -> step[p]?.let { p to it } })
    }

    /**
     * Whether two tap proposals target the same visible control closely enough
     * that retrying them would just click it twice. Models often move a point by
     * a few pixels between replies, so exact JSON equality is too weak here.
     */
    fun sameTap(a: JObject, b: JObject, tolerance: Long = 30L): Boolean {
        if (a["action"]?.str != "tap" || b["action"]?.str != "tap") return false
        val ax = a["x"]?.double ?: return false
        val ay = a["y"]?.double ?: return false
        val bx = b["x"]?.double ?: return false
        val by = b["y"]?.double ?: return false
        return abs(ax - bx) <= tolerance && abs(ay - by) <= tolerance
    }

    /** Same action for the retry guard; taps use a small coordinate tolerance. */
    fun sameForRetry(a: JObject, b: JObject): Boolean =
        if (sameTap(a, b)) true else key(a) == key(b)

    private fun group(re: Regex, text: String): String? = re.find(text)?.groupValues?.get(1)
}

/**
 * Screen points (fractions) the pointer passes through for one action, so the "did the screen change" check
 * can ignore them. On Android `home` and `back` are key presses (the board's consumer control), so the
 * pointer stays where it is and there is nothing to mask - unlike the iPhone app, which swipes for them.
 */
fun pointerPath(act: JObject): List<Pt> {
    fun n(k: String) = (act[k]?.double ?: 0.0) / 1000.0
    return when (act["action"]?.str) {
        "tap" -> listOf(Pt(n("x"), n("y")))
        "swipe" -> listOf(Pt(n("x1"), n("y1")), Pt(n("x2"), n("y2")))
        "path" -> pointsOf(act).map { Pt(it.x / 1000.0, it.y / 1000.0) }
        "circle" -> listOf(Pt(n("x") + n("r"), n("y")))
        else -> emptyList()
    }
}

/** The [[x, y], ...] points of a path action, in 0-1000 units. */
fun pointsOf(act: JObject): List<Pt> {
    val items = act["points"]?.arr ?: return emptyList()
    return items.mapNotNull { p ->
        val pair = p.arr ?: return@mapNotNull null
        val x = pair.getOrNull(0)?.double ?: return@mapNotNull null
        val y = pair.getOrNull(1)?.double ?: return@mapNotNull null
        Pt(x, y)
    }
}

/** The circle action as the points of one stroke. */
fun circleOf(act: JObject, aspect: Double): List<Pt> {
    fun n(k: String) = (act[k]?.double ?: 0.0) / 1000.0
    return circlePoints(n("x"), n("y"), n("r"), aspect)
}
