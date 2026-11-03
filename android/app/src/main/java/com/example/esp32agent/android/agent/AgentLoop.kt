package com.example.esp32agent.android.agent

import com.example.esp32agent.android.board.BlindError
import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.LlmError
import com.example.esp32agent.android.gestures.Gestures
import com.example.esp32agent.android.net.ChatModel
import com.example.esp32agent.android.net.LlmClient
import com.example.esp32agent.android.net.Progress
import com.example.esp32agent.android.screen.ScreenSource
import com.example.esp32agent.android.store.TraceStore
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.Pt
import com.example.esp32agent.android.util.now
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The see-think-act loop: a planner model writes the plan, an actor model picks each step, the board performs
 * it on the phone, and the next look tells the actor whether the screen changed.
 * Port of Agent/AgentLoop.swift (which ports the legacy server's agent.py), so all three implementations read
 * the same prompts, parse the same grammar and write the same trace.
 *
 * What differs on Android: the screen comes from a local capture instead of AirPlay-over-BLE, and `home` and
 * `back` are the board's consumer keys instead of edge swipes, so there is no pointer path to mask for them.
 */
class AgentLoop(
    private val scope: CoroutineScope,
    val phone: Gestures?,
    val screen: ScreenSource?,
    var actor: ChatModel?,
    var planner: ChatModel?,
    val trace: TraceStore?,
    val prompts: Prompts,
    val maxSteps: Int = 40,
    val maxFailures: Int = 3,
) {
    data class Planned(val act: JObject, val shot: ByteArray?)

    /** What the agent is busy with between steps, for the run notification. */
    sealed class Doing {
        data object Looking : Doing()
        data object Waiting : Doing()
        data object Planning : Doing()
        data object Thinking : Doing()
        data object Retrying : Doing()
        data class Acting(val act: JObject) : Doing()
    }

    enum class HelpChoice { RESUME, SKIP, STOP }

    private val _task = MutableStateFlow("")
    val task: StateFlow<String> = _task.asStateFlow()
    private val _plan = MutableStateFlow("")
    val plan: StateFlow<String> = _plan.asStateFlow()
    private val _steps = MutableStateFlow<List<JObject>>(emptyList())
    val steps: StateFlow<List<JObject>> = _steps.asStateFlow()
    private val _shots = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val shots: StateFlow<Map<Int, ByteArray>> = _shots.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val stepList = ArrayList<JObject>()
    private var pointerAt = Pt(0.0, 0.0)

    /** When the last gesture ended: the model never decides on a frame from before it. */
    var lastActed = 0.0
        private set

    private var helpAsked = 0
    private var job: Job? = null

    /** Awaited before every look and every gesture (the app holds the run while its own UI is in front). */
    var gate: (suspend () -> Unit)? = null

    /** Recovery from a lost link: the link state, each stage as it changes (null = live), what to redo. */
    var link: (() -> Recovery.Link)? = null
    var onRecover: ((Recovery.Stage?) -> Unit)? = null
    var restore: (suspend () -> Unit)? = null
    var recoveryLimit: Double = Recovery.LIMIT

    var onStep: (() -> Unit)? = null
    var onDoing: ((Doing) -> Unit)? = null
    var onFinish: (() -> Unit)? = null

    /** "Needs your help": asked when the actor gives up or errors repeat; null = end the run as before. */
    var onHelp: (suspend (String) -> HelpChoice)? = null

    /** Hints from a saved task's checklist, appended to the actor's system prompt. */
    var hints: List<String> = emptyList()

    /** Extra system rules for this phone (the iPhone app appends its AssistiveTouch rule here). */
    var systemRules: () -> String = { "" }

    private val maxTokens = mapOf("planner" to 1500, "actor" to 1000)

    /** traceStarted: the caller already opened the run's trace folder (so a failed start is logged too). */
    fun start(task: String, traceStarted: Boolean = false) {
        if (_running.value) throw DeviceError("agent already running")
        if (screen?.live != true) throw DeviceError("no phone screen: allow the screen capture first")
        _task.value = task
        _plan.value = ""
        stepList.clear()
        _steps.value = emptyList()
        _shots.value = emptyMap()
        pointerAt = Pt(0.0, 0.0)
        lastActed = 0.0
        helpAsked = 0
        if (!traceStarted) trace?.start(task)
        _running.value = true
        job = scope.launch {
            try {
                run()
            } finally {
                _running.value = false
                onFinish?.invoke()
            }
        }
    }

    fun stop() {
        val j = job
        if (j != null) j.cancel() else _running.value = false  // a shown (demo) run has no job
    }

    /** Wait for the current run to end; the service ends the notification and the capture after it. */
    suspend fun join() {
        job?.join()
    }

    fun clear() {
        if (_running.value) throw DeviceError("stop the agent first")
        _task.value = ""
        _plan.value = ""
        stepList.clear()
        _steps.value = emptyList()
        _shots.value = emptyMap()
    }

    /** Show a run without running it (a stored trace). */
    fun showStored(task: String, plan: String, steps: List<JObject>, shots: Map<Int, ByteArray> = emptyMap()) {
        _task.value = task
        _plan.value = plan
        stepList.clear()
        stepList.addAll(steps)
        _steps.value = steps
        _shots.value = shots
    }

    private fun record(shot: ByteArray?, step: JObject) {
        if (shot != null) _shots.value = _shots.value + (stepList.size to shot)
        stepList.add(jObj("t" to now().j(), "shot" to (shot != null).j()).merging(step))
        _steps.value = stepList.toList()
        trace?.write(kind = "step", record = step)
        onStep?.invoke()
    }

    private fun lastStep(): JObject? = stepList.lastOrNull()

    private fun setLast(key: String, value: JValue) {
        if (stepList.isEmpty()) return
        stepList[stepList.size - 1][key] = value
        _steps.value = stepList.toList()
    }

    /**
     * The screen is gone (the board dropped, or the capture stopped): pause the run until it is back, in
     * stages, instead of failing it. Returns how many seconds it took, or null if the screen was there.
     */
    suspend fun waitForScreen(poll: Double = 0.5): Double? {
        val s = screen
        if (s != null && s.live) return null
        val recovery = Recovery(now(), recoveryLimit)
        var shown: Recovery.Stage? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val state = link?.invoke() ?: Recovery.Link(
                    connected = phone?.device?.connected ?: true,
                    capturing = s?.live == true,
                    frameAt = if (s?.live == true) now() else 0.0,
                )
                when (val next = recovery.next(state, now())) {
                    is Recovery.Next.Live -> {
                        if (s != null && s.live) {
                            restore?.invoke()
                            s.settled()
                            return now() - recovery.startedAt
                        }
                    }
                    is Recovery.Next.Wait -> if (next.stage != shown) {
                        shown = next.stage
                        onRecover?.invoke(next.stage)
                    }
                    is Recovery.Next.GiveUp -> throw BlindError(next.reason)
                }
                delay((poll * 1000).toLong())
            }
        } finally {
            if (shown != null) onRecover?.invoke(null)
        }
    }

    private suspend fun ask(
        role: String,
        llm: ChatModel,
        system: String,
        prompt0: String,
        temperature: Double = 0.0,
        retryTimeout: Boolean = true,
    ): Triple<String, Double, ByteArray?> {
        gate?.invoke()
        var prompt = prompt0
        val lost = waitForScreen()
        if (lost != null) {
            record(
                null,
                jObj(
                    "action" to "reconnected".j(),
                    "reason" to String.format(Locale.US, "connection lost for %.0f s and restored", lost).j(),
                ),
            )
            prompt += "\n\nNote: the connection was lost and restored; re-check the screen before acting."
            gate?.invoke()  // the user may have opened this app while it was paused
        }
        val s = screen ?: throw BlindError()
        if (!s.live) throw BlindError()  // never let the model act blind
        val frame = s.frame
        val started = now()
        onDoing?.invoke(if (retryTimeout) Doing.Waiting else Doing.Retrying)
        llm.onProgress = { p ->
            onDoing?.invoke(
                when (p) {
                    Progress.WAITING -> if (retryTimeout) Doing.Waiting else Doing.Retrying
                    Progress.RECEIVING -> if (role == "planner") Doing.Planning else Doing.Thinking
                }
            )
        }
        val reply = try {
            llm.chat(system, prompt, frame, maxTokens[role] ?: 1000, temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val timedOut = LlmClient.isTimeout(e)
            if (!retryTimeout || !timedOut || !currentCoroutineContext().isActive) throw e
            DebugLog.write("model request timed out ($role); retrying once with a fresh screen")
            onDoing?.invoke(Doing.Retrying)
            delay(2000)
            // Re-enter the gate and the screen checks before retrying; no gesture is repeated.
            return ask(role, llm, system, prompt, temperature, retryTimeout = false)
        } finally {
            llm.onProgress = null
        }
        val seconds = Math.round((now() - started) * 100) / 100.0
        val rec = jObj(
            "role" to role.j(), "model" to llm.model.j(), "prompt" to prompt.j(),
            "reply" to reply.text.j(), "seconds" to seconds.j(),
        )
        reply.firstToken?.let { rec["first_token_s"] = (Math.round(it * 100) / 100.0).j() }
        if (lastActed > 0 && s.capturedAt > 0) {
            rec["frame_after_action_s"] = (Math.round((s.capturedAt - lastActed) * 100) / 100.0).j()
        }
        trace?.write(frame, kind = "llm", record = rec.merging(reply.diagnostics))
        return Triple(reply.text, seconds, frame)
    }

    private suspend fun run() {
        var failures = 0
        try {
            replan()
            loop@ for (i in 0 until maxSteps) {
                try {
                    if (step()) {
                        val gaveUp = lastStep()?.get("action")?.str == "fail"
                        if (!gaveUp || !askForHelp()) {
                            finish()
                            return
                        }
                        continue@loop
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BlindError) {
                    throw e
                } catch (e: Exception) {  // a bad reply or a dropped link: recover, don't crash
                    record(
                        null,
                        jObj("action" to "hiccup".j(), "reason" to (e.message ?: e.javaClass.simpleName).take(300).j()),
                    )
                    val linkLost = e is DeviceError && screen?.live == false
                    if (!linkLost) failures += 1  // a lost link is not the agent's fault
                    if (failures >= maxFailures) {
                        record(null, jObj("action" to "fail".j(), "reason" to "$failures errors in a row".j()))
                        if (!askForHelp()) {
                            finish()
                            return
                        }
                        failures = 0
                        continue@loop
                    }
                    screen?.settled()
                    replan(stuck = true)
                }
            }
            record(null, jObj("action" to "fail".j(), "reason" to "step limit reached".j()))
        } catch (e: CancellationException) {
            record(null, jObj("action" to "stopped".j()))
            throw e
        } catch (e: BlindError) {
            record(null, jObj("action" to "fail".j(), "reason" to (e.message ?: "the screen is gone").j()))
        } catch (e: Exception) {  // e.g. the planner itself is unreachable
            record(null, jObj("action" to "error".j(), "reason" to LlmClient.message(e).j()))
        }
        finish()
    }

    /** Pauses for the user (at most twice a run). True = go on from the current screen with a new plan. */
    private suspend fun askForHelp(): Boolean {
        val handler = onHelp
        if (handler == null || helpAsked >= 2) return false
        helpAsked += 1
        val reason = lastStep()?.get("reason")?.str ?: ""
        record(null, jObj("action" to "help".j(), "reason" to reason.j()))
        val choice = handler(reason)
        currentCoroutineContext().ensureActive()
        when (choice) {
            HelpChoice.STOP -> {
                record(null, jObj("action" to "stopped".j()))
                return false
            }
            HelpChoice.RESUME -> record(
                null,
                jObj("action" to "takeover".j(), "reason" to "the user did this part by hand: $reason".j()),
            )
            HelpChoice.SKIP -> record(
                null,
                jObj("action" to "skipped".j(), "reason" to "the user asked to skip this step: $reason".j()),
            )
        }
        screen?.settled()
        replan(stuck = true)
        return true
    }

    /** The actor's system prompt: the shared rules, this phone's rules, and a task's checklist as hints. */
    val actorPrompt: String
        get() {
            val rules = systemRules()
            val base = if (rules.isEmpty()) prompts.actor else prompts.actor + "\n" + rules
            return if (hints.isEmpty()) base else base + "\n\nHints for this task:\n" + hints.joinToString("\n") { "- $it" }
        }

    private fun finish() {
        trace?.writeMetrics(
            jObj(
                "task" to _task.value.j(),
                "steps" to stepList.size.j(),
                "plan" to _plan.value.j(),
                "platform" to "android".j(),
                "pointer" to (if (phone?.exact == true) "absolute" else "relative").j(),
            ),
        )
    }

    /** One see-think-act round (one or more actions). True when the task is over. */
    private suspend fun step(): Boolean {
        var hidMs = 0.0
        var frameMs = 0.0
        val acts = nextActions()
        val seen = acts[0].shot
        val spots = ArrayList<Pt>()
        spots.add(pointerAt)  // where the pointer was and goes: pointer moves are not screen changes
        val stuck = repeating(acts[0].act)
        try {
            for ((i, planned) in acts.withIndex()) {
                val act = planned.act
                val name = act["action"]?.str ?: ""
                if (name == "replan" || stuck) {
                    record(planned.shot, act)
                    replan(stuck)
                    return false
                }
                if (name != "done" && name != "fail") {
                    gate?.invoke()
                    onDoing?.invoke(Doing.Acting(act))  // the thought (on the first act) names the target
                    val started = now()
                    perform(act)
                    lastActed = now()
                    val took = lastActed - started
                    hidMs += took * 1000
                    act["act_s"] = (Math.round(took * 100) / 100.0).j()
                    spots.addAll(pointerPath(act))
                    pointerAt = spots.last()
                }
                record(planned.shot, act)
                if (name == "done" || name == "fail") return true
                val following = if (i < acts.size - 1) acts[i + 1].act["action"]?.str else null
                if (name == "tap" && (following == "type" || following == "key")) {  // a missed tap never opened the field
                    frameMs += settle()
                    if (!Vision.changed(seen, screen?.frame, spots)) {
                        setLast("changed", false.j())
                        return false  // skip the rest; the actor looks again
                    }
                }
            }
            frameMs += settle()
            // feedback for the next round
            setLast("changed", Vision.changed(seen, screen?.frame, spots).j())
            return false
        } finally {
            DebugLog.write(String.format(Locale.US, "step: llm %.0f ms, hid %.0f ms, frame %.0f ms",
                (acts[0].act["think_s"]?.double ?: 0.0) * 1000, hidMs, frameMs))
        }
    }

    private suspend fun settle(): Double {
        val started = now()
        onDoing?.invoke(Doing.Looking)
        screen?.afterAction(lastActed)
        return (now() - started) * 1000
    }

    /** The previous round changed nothing on screen, so a tap may have landed off target. */
    private val missed: Boolean get() = lastStep()?.get("changed")?.bool == false

    /**
     * True when the actor picks the same action as its last limit-1 moves and the last one did nothing.
     * Repeating a move that still changes the screen (scrolling a long list) is progress, not a loop.
     */
    fun repeating(act: JObject, limit: Int = 3): Boolean {
        val moves = stepList.filter { it["plan"] == null }.takeLast(limit - 1)
        return moves.size == limit - 1 &&
            moves.all { Actions.key(it) == Actions.key(act) } &&
            moves.lastOrNull()?.get("changed")?.bool != true
    }

    suspend fun replan(stuck: Boolean = false) {
        val p = planner ?: throw LlmError("no planner model")
        var prompt = "Goal: ${_task.value}"
        if (stuck) {
            val last = lastStep() ?: JObject()
            prompt += "\n\nThe previous plan got stuck (last event: " + pyStr(last["action"]) + " " +
                (last["reason"]?.str ?: "") + "):\n" + _plan.value +
                "\nLook at the current screen and plan from here, another way if needed."
        }
        val (reply, seconds, frame) = ask("planner", p, plannerPrompt, prompt)
        val (en, zh) = Actions.parsePlan(reply)
        _plan.value = en
        record(
            frame,
            jObj(
                "action" to (if (stuck) "replan (stuck)" else "plan").j(),
                "plan" to en.j(), "plan_zh" to zh.j(), "think_s" to seconds.j(),
            ),
        )
    }

    /** The planner's system prompt, with this phone's rules appended. */
    val plannerPrompt: String
        get() {
            val rules = systemRules()
            return if (rules.isEmpty()) prompts.plannerApp else prompts.plannerApp + "\n" + rules
        }

    suspend fun nextActions(): List<Planned> {
        val a = actor ?: throw LlmError("no actor model")
        // actions and their effect only: echoing earlier thoughts makes the model repeat them instead of looking
        val hidden = setOf("t", "plan", "plan_zh", "think_s", "act_s", "shot", "thought", "thought_zh", "changed")
        val history = stepList.filter { it["plan"] == null }.takeLast(8).joinToString("\n") { s ->
            val effect = when (s["changed"]?.bool) {
                true -> " -> screen changed"
                false -> " -> screen did NOT change"
                null -> ""
            }
            PyJSON.dumps(s.without(hidden)).take(300) + effect
        }
        var prompt = "Goal: ${_task.value}\n\nPlan:\n${_plan.value}\n\nRecent actions:\n" +
            (if (history.isEmpty()) "(none)" else history) + "\n\nNext action?"
        var reply = ""
        for (attempt in 0 until 3) {  // retry unparseable replies, a little less deterministically
            val (text, seconds, frame) = ask("actor", a, actorPrompt, prompt, temperature = 0.4 * attempt)
            reply = text
            val acts: List<JObject> = try {
                Actions.parseActions(text)
            } catch (e: Actions.ParseError) {
                continue
            }
            val acts2 = acts.toMutableList()
            if (missed && stepList.isNotEmpty() && Actions.key(acts2[0]) == Actions.key(stepList.last())) {
                prompt += "\n\nYou just proposed " + PyJSON.dumps(Actions.key(acts2[0])) + " again, but it changed " +
                    "nothing last time. Choose a different action (another position, a shorter swipe, or another way)."
                continue
            }
            acts2[0]["think_s"] = seconds.j()
            return acts2.map { Planned(it, frame) }
        }
        throw Actions.ParseError("actor gave no valid action: " + reply.take(200))
    }

    private suspend fun perform(a: JObject) {
        val phone = phone ?: throw DeviceError("no phone")
        fun p(k: String) = (a[k]?.double ?: 0.0) / 1000.0
        when (a["action"]?.str) {
            "tap" -> phone.tap(p("x"), p("y"), aim = a["precise"]?.bool == true || missed)
            "swipe" -> phone.swipe(p("x1"), p("y1"), p("x2"), p("y2"), hold = true)
            "path" -> phone.drag(pointsOf(a).map { Pt(it.x / 1000, it.y / 1000) }, hold = true)
            "circle" -> phone.drag(circleOf(a, screen?.aspect ?: 9.0 / 19.5), hold = true)
            "type" -> phone.type(a["text"]?.str ?: "")
            "key" -> phone.key(a["combo"]?.str ?: "")
            "home" -> phone.goHome()
            "back" -> phone.goBack()
            "wait" -> Unit
            else -> throw Actions.ParseError("unknown action " + (a["action"]?.str ?: "nil"))
        }
    }

    /** Python's str() of a value in an f-string (None for a missing key). */
    private fun pyStr(v: JValue?): String = when (v) {
        null -> "None"
        is JValue.StrV -> v.v
        else -> PyJSON.dumps(v)
    }
}
