package com.example.esp32agent.android

import android.content.Context
import com.example.esp32agent.android.agent.Prompts
import com.example.esp32agent.android.board.BoardLink
import com.example.esp32agent.android.board.LlmError
import com.example.esp32agent.android.gestures.AbsGestures
import com.example.esp32agent.android.gestures.Gestures
import com.example.esp32agent.android.gestures.RelGestures
import com.example.esp32agent.android.gestures.probeAbsolute
import com.example.esp32agent.android.net.ChatModel
import com.example.esp32agent.android.net.LlmClient
import com.example.esp32agent.android.screen.CaptureScreen
import com.example.esp32agent.android.store.Settings
import com.example.esp32agent.android.store.TraceStore
import com.example.esp32agent.android.util.DebugLog
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * The pieces of one run, wired together: the board link (hands), the capture (eyes), the models (brain) and
 * the trace. The iPhone app builds the same graph in AppModel/RunSession; here a foreground service owns it,
 * so the run survives the activity going to the background while the agent works in another app.
 */
class AppGraph(context: Context, val scope: CoroutineScope) {
    val appContext: Context = context.applicationContext
    val settings = Settings(appContext)
    val prompts = Prompts(appContext)
    val trace = TraceStore.defaultStore(appContext)
    val board = BoardLink(appContext, scope)
    val capture = CaptureScreen(appContext)

    /** The actor model, and the planner (the same one unless a separate planner model is set). */
    fun models(): Pair<ChatModel, ChatModel>? {
        if (!settings.hasModel) return null
        val actor = LlmClient(
            baseURL = settings.baseUrl, model = settings.actorModel, apiKey = settings.apiKey,
            tokenParam = settings.tokenParam,
        )
        val planner = if (settings.plannerModel.isEmpty() || settings.plannerModel == settings.actorModel) {
            actor
        } else {
            LlmClient(
                baseURL = settings.baseUrl, model = settings.plannerModel, apiKey = settings.apiKey,
                tokenParam = settings.tokenParam,
            )
        }
        return actor to planner
    }

    /** A client for the settings as they are now, for the setup screen's "list models" and "test". */
    fun client(model: String = settings.actorModel): LlmClient = LlmClient(
        baseURL = settings.baseUrl, model = model, apiKey = settings.apiKey, tokenParam = settings.tokenParam,
    )

    /** The endpoint's model ids, for the picker (an endpoint without /models answers with an error). */
    suspend fun listModels(): List<String> = client().models()

    /** One tiny call, so the user knows the endpoint, key and model work before a run. */
    suspend fun testModel(): String {
        val model = client()
        val started = System.currentTimeMillis()
        val reply = model.chat(
            system = "Reply with the single word ok.",
            text = "Check.",
            image = null,
            maxTokens = 128,
            temperature = 0.0,
        )
        val ms = System.currentTimeMillis() - started
        if (reply.text.isBlank()) {
            throw LlmError("the model returned no answer; disable thinking or increase the output token budget")
        }
        return model.model + ": " + reply.text + " in " + ms + " ms"
    }

    /**
     * The gestures for the board on the link. `auto` asks the board: an Android firmware build answers `abs`,
     * the stock (iPhone) build does not and gets the calibrated relative pointer instead.
     */
    suspend fun gestures(): Gestures {
        val mode = settings.pointerMode
        val absolute = when (mode) {
            "abs" -> true
            "rel" -> false
            else -> probeAbsolute(board.commands)
        }
        DebugLog.write("pointer: " + if (absolute) "absolute (android firmware)" else "relative (stock firmware)")
        AgentRuntime.setPointer(if (absolute) "absolute" else "relative")
        return if (absolute) AbsGestures(board.commands) else relGestures()
    }

    /** The relative backend needs a calibration, which it keeps in the app's files. */
    fun relGestures(): RelGestures = RelGestures(
        device = board.commands,
        calibrationFile = File(appContext.filesDir, "calibration.json"),
        screen = capture,
    ).also { it.screenAspect = { capture.aspect } }

    /** Release what a run holds. The board link belongs to the process, so it is kept for the next run. */
    fun close() {
        capture.stop()
    }
}
