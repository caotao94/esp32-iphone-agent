package com.example.esp32agent.android

import com.example.esp32agent.android.agent.JObject
import com.example.esp32agent.android.agent.StepText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the UI shows about the current run. The service owns the real objects ([AppGraph], AgentLoop) and
 * publishes here, so the activity can be recreated - or gone - while a run continues in another app.
 */
object AgentRuntime {
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

    /** What the agent is busy with, in words, for the notification and Home. */
    private val _doing = MutableStateFlow("")
    val doing: StateFlow<String> = _doing.asStateFlow()

    /** A line for the user while a run is paused (recovery) or ended (why). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Which pointer the board has: "absolute" (Android firmware) or "relative" (stock + calibration). */
    private val _pointer = MutableStateFlow<String?>(null)
    val pointer: StateFlow<String?> = _pointer.asStateFlow()

    /** The trace folder of the run, so Home can offer a replay. */
    private val _runDir = MutableStateFlow<String?>(null)
    val runDir: StateFlow<String?> = _runDir.asStateFlow()

    fun publish(task: String, plan: String, steps: List<JObject>, shots: Map<Int, ByteArray>) {
        _task.value = task
        _plan.value = plan
        _steps.value = steps
        _shots.value = shots
    }

    fun setDoing(text: String) {
        _doing.value = text
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setNotice(text: String?) {
        _notice.value = text
    }

    fun reset() {
        _task.value = ""
        _plan.value = ""
        _steps.value = emptyList()
        _shots.value = emptyMap()
        _doing.value = ""
        _notice.value = null
        _pointer.value = null
        _runDir.value = null
    }

    fun setRunDir(path: String?) {
        _runDir.value = path
    }

    fun setPointer(pointer: String?) {
        _pointer.value = pointer
    }

    /** The newest step in words, for the notification. */
    fun latestLine(): String {
        val last = _steps.value.lastOrNull() ?: return _doing.value
        return StepText.line(last)
    }
}