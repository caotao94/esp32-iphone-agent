package com.example.esp32agent.android.net

import com.example.esp32agent.android.agent.JObject
import com.example.esp32agent.android.agent.JValue
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj

/** One model reply, with the timings and diagnostics the trace stores. Port of ChatResult in LLMClient.swift. */
data class ChatResult(
    var text: String,
    var firstToken: Double? = null,     // seconds until the first streamed token
    var total: Double = 0.0,
    var finishReason: String? = null,   // "stop", "length" (max tokens hit), ...
    var usage: Map<String, Long> = emptyMap(),
    var reasoningChars: Int = 0,        // reasoning_content the model wrote (not part of `text`)
    var rawChars: Int = 0,              // content before trimming
    var responseSeconds: Double? = null, // time to HTTP headers, including server queue time
    var retried: Boolean = false,        // the first reply was empty
) {
    /** For the trace and the debug log: why a reply came back empty or cut. */
    val diagnostics: JObject
        get() {
            val d = jObj(
                "finish_reason" to (finishReason ?: "-").j(),
                "reasoning_chars" to reasoningChars.j(),
                "raw_chars" to rawChars.j(),
            )
            if (usage.isNotEmpty()) {
                d["usage"] = JObject(usage.keys.sorted().map { k -> k to (usage[k] ?: 0L).j() }).j()
            }
            if (retried) d["retried_empty"] = true.j()
            if (text.isBlank() && reasoningChars > 0) d["note"] = "empty content, reasoning only".j()
            return d
        }
}

/** What the model client is doing, for the run notification. */
enum class Progress { WAITING, RECEIVING }

/** A chat model the agent can ask ([LlmClient], or a fake in tests). */
interface ChatModel {
    val model: String
    var onProgress: ((Progress) -> Unit)?

    suspend fun chat(
        system: String,
        text: String,
        image: ByteArray?,
        maxTokens: Int,
        temperature: Double,
    ): ChatResult
}

/** A model endpoint that can also list its models, for the picker. */
interface ModelLister {
    suspend fun models(): List<String>
}

/** An empty or refused reply: the run counts it as a failure and asks the model again. */
class EmptyReplyError(val result: ChatResult) : RuntimeException("the model replied with no text")
