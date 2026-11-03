package com.example.esp32agent.android.store

import android.content.Context
import com.example.esp32agent.android.agent.JObject
import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.PyJSON
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.now
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug traces: one folder per run with every model call, the screenshot it decided on and every recorded
 * step. The layout matches the iPhone app's (docs/run-data-export.md), so the same review tooling reads a
 * run from either phone:
 *
 *     traces/<yyyyMMdd-HHmmss>/
 *       steps.jsonl     one JSON object per event: n, t, kind = task | llm | step
 *       002.jpg, ...    the frames, named by the event that stored them
 *       metrics.json    when a metrics snapshot was written
 *
 * Port of Services/TraceStore.swift.
 */
class TraceStore(val root: File) {
    var dir: File? = null
        private set
    private var n = 0

    fun start(task: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val d = File(root, stamp)
        if (!d.exists() && !d.mkdirs()) {
            DebugLog.write("trace: cannot create ${d.path}")
            dir = null
            return
        }
        dir = d
        n = 0
        write(kind = "task", record = jObj("task" to task.j()))
    }

    /** One event; when [frame] is given it is stored as `<n>.jpg` and named in the record. */
    fun write(frame: ByteArray? = null, kind: String, record: JObject) {
        val d = dir ?: return
        n += 1
        val line = jObj("n" to n.j(), "t" to now().j(), "kind" to kind.j()).merging(record)
        if (frame != null && frame.isNotEmpty()) {
            val name = String.format(Locale.US, "%03d.jpg", n)
            line["frame"] = name.j()
            runCatching { File(d, name).writeBytes(frame) }
                .onFailure { DebugLog.write("trace: frame not saved: ${it.message}") }
        }
        append(PyJSON.dumps(line, ascii = false) + "\n", File(d, "steps.jsonl"))
    }

    fun writeMetrics(metrics: JObject) {
        val d = dir ?: return
        runCatching { File(d, "metrics.json").writeText(PyJSON.dumps(metrics, ascii = false)) }
    }

    fun close() {
        dir = null
    }

    /** Every run folder, newest first. */
    fun runs(): List<File> =
        (root.listFiles { f -> f.isDirectory && File(f, "steps.jsonl").exists() } ?: emptyArray())
            .sortedByDescending { it.name }

    private fun append(text: String, file: File) {
        runCatching { file.appendText(text) }
            .onFailure { DebugLog.write("trace: ${it.message}") }
    }

    companion object {
        fun defaultStore(context: Context) = TraceStore(File(context.filesDir, "traces"))

        /** The events of one run, for a replay list. */
        fun load(run: File): List<JObject> = File(run, "steps.jsonl")
            .takeIf { it.exists() }
            ?.readLines()
            ?.mapNotNull { line -> runCatching { JsonParse.parseObject(line) }.getOrNull() }
            ?: emptyList()

        /** The frame file a record refers to, if it stored one. */
        fun frameOf(run: File, record: JObject): File? =
            record["frame"]?.str?.let { File(run, it) }?.takeIf { it.exists() }
    }
}