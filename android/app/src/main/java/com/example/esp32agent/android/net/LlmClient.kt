package com.example.esp32agent.android.net

import com.example.esp32agent.android.agent.JObject
import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.JValue
import com.example.esp32agent.android.agent.PyJSON
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj
import com.example.esp32agent.android.board.LlmError
import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.normalizeApiKey
import com.example.esp32agent.android.util.now
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/**
 * A client for any OpenAI-compatible chat endpoint that accepts images (a cloud API, vLLM, SGLang,
 * llama.cpp, Ollama). Port of Services/LLMClient.swift, which ports the legacy server's llm.py: replies are
 * streamed (SSE), because a proxy such as Cloudflare cuts a request that sends nothing for ~100 s, and a
 * server that ignores `stream` and answers plain JSON works too.
 */
class LlmClient(
    baseURL: String,
    override val model: String,
    apiKey: String = "",
    private val extra: Map<String, JValue> = emptyMap(),
    tokenParam: String = AUTO,
    private val client: OkHttpClient = defaultClient(),
) : ChatModel, ModelLister {

    override var onProgress: ((Progress) -> Unit)? = null

    private val base: String = baseURL.trim().trimEnd('/')
    private val apiKey: String = normalizeApiKey(apiKey)
    private var param: String = if (tokenParam == AUTO) "max_tokens" else tokenParam

    companion object {
        const val AUTO = "auto"
        val TOKEN_PARAMS = listOf("max_tokens", "max_completion_tokens")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** A long read timeout: a planner call on a busy server can take a while before its first token. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** A timeout is worth one retry with a fresh screen; anything else is not. */
        fun isTimeout(e: Throwable): Boolean = e is InterruptedIOException || e is SocketTimeoutException ||
            (e is IOException && (e.message ?: "").contains("timeout", ignoreCase = true))

        fun message(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(300)
    }

    override suspend fun chat(
        system: String,
        text: String,
        image: ByteArray?,
        maxTokens: Int,
        temperature: Double,
    ): ChatResult = withContext(Dispatchers.IO) {
        val started = now()
        onProgress?.invoke(Progress.WAITING)
        val body = jObj(
            "model" to model.j(),
            "messages" to listOf(
                jObj("role" to "system".j(), "content" to system.j()).j(),
                userMessage(text, image).j(),
            ).j(),
            "stream" to true.j(),
            "stream_options" to jObj("include_usage" to true.j()).j(),
            "temperature" to temperature.j(),
        )
        // Qwen3.8-Flash spends its output budget on thinking by default. The agent needs the
        // action in content; a short connection test can otherwise finish with reasoning only.
        val host = base.toHttpUrlOrNull()?.host.orEmpty()
        val aliyun = host.endsWith(".maas.aliyuncs.com") || host == "dashscope.aliyuncs.com" ||
            host == "dashscope-intl.aliyuncs.com" || host == "dashscope-us.aliyuncs.com" ||
            host.endsWith(".dashscope.aliyuncs.com")
        if (aliyun && (model == "qwen3.8-flash" || model.startsWith("qwen3.8-flash-"))) {
            body["enable_thinking"] = false.j()
        }
        extra.forEach { (k, v) -> body[k] = v }
        var result = post(body, maxTokens, started)
        if (result.text.isBlank() && result.reasoningChars == 0) {
            // Some providers answer 200 with an empty delta stream now and then: ask again, once.
            DebugLog.write("llm: empty reply, asking again")
            result = post(body, maxTokens, started).also { it.retried = true }
        }
        result.total = now() - started
        if (result.text.isBlank() && result.reasoningChars == 0) throw EmptyReplyError(result)
        result.text = result.text.trim()
        result
    }

    override suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/models").get().auth().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: ""
            if (!response.isSuccessful) throw LlmError("HTTP ${response.code} from $base/models")
            val json = runCatching { JsonParse.parseObject(raw) }.getOrNull() ?: throw LlmError("bad model list reply")
            val list = json["data"]?.arr ?: emptyList()
            val seen = LinkedHashSet<String>()
            list.forEach { item ->
                val id = (item as? JValue.ObjV)?.v?.let { it["id"]?.str ?: it["name"]?.str }
                if (!id.isNullOrEmpty()) seen.add(id)
            }
            seen.toList()
        }
    }

    private fun userMessage(text: String, image: ByteArray?): JObject {
        if (image == null || image.isEmpty()) return jObj("role" to "user".j(), "content" to text.j())
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image)
        val parts = listOf(
            jObj("type" to "image_url".j(), "image_url" to jObj("url" to url.j()).j()).j(),
            jObj("type" to "text".j(), "text" to text.j()).j(),
        )
        return jObj("role" to "user".j(), "content" to parts.j())
    }

    private fun Request.Builder.auth(): Request.Builder = apply {
        // Validate before OkHttp constructs the header: its exception can include the secret value.
        if (apiKey.any { it !in '!'..'~' }) {
            throw LlmError("API key contains unsupported characters. Paste only the key from your provider.")
        }
        if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
    }

    /** One POST, streamed when the server streams. Switches the token parameter once if the server rejects it. */
    private fun post(body: JObject, maxTokens: Int, started: Double): ChatResult {
        for (attempt in 0..1) {
            val payload = PyJSON.dumps(body.merging(jObj(param to maxTokens.j()))).toRequestBody(JSON)
            val request = Request.Builder().url("$base/chat/completions").post(payload).auth().build()
            client.newCall(request).execute().use { response ->
                val responseSeconds = now() - started
                val type = response.header("Content-Type") ?: ""
                DebugLog.write(
                    "llm headers: HTTP ${response.code} $type after " +
                        String.format(Locale.US, "%.1f", now() - started) + " s"
                )
                if (!response.isSuccessful) {
                    val raw = response.body?.string() ?: ""
                    val wanted = TOKEN_PARAMS.firstOrNull { raw.contains(it) }
                    if (attempt == 0 && wanted != null && wanted != param) {
                        DebugLog.write("llm: the endpoint wants $wanted; retrying with it")
                        param = wanted
                        return@use
                    }
                    throw LlmError("HTTP ${response.code}: " + raw.take(300))
                }
                val source: BufferedSource = response.body?.source() ?: throw LlmError("empty LLM reply")
                val result = if (type.contains("text/event-stream")) {
                    parseStream(source, started)
                } else {
                    parseMessage(source.readUtf8())
                }
                result.responseSeconds = responseSeconds
                return result
            }
        }
        throw LlmError("the endpoint rejected the token parameter")
    }

    private fun parseStream(source: BufferedSource, started: Double): ChatResult {
        val result = ChatResult(text = "")
        var reported = false
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            val json = runCatching { JsonParse.parseObject(payload) }.getOrNull() ?: continue
            checkError(json)
            val choice = json["choices"]?.arr?.firstOrNull()?.obj
            val delta = choice?.get("delta")?.obj
            note(json, choice, result)
            val reasoning = reasoning(delta)
            val piece = delta?.get("content")?.str ?: ""
            if (!reported && (reasoning.isNotEmpty() || piece.isNotEmpty())) {
                reported = true
                onProgress?.invoke(Progress.RECEIVING)
            }
            result.reasoningChars += reasoning.length
            if (piece.isEmpty()) continue
            if (result.firstToken == null) result.firstToken = now() - started
            result.text += piece
        }
        result.rawChars = result.text.length
        return result
    }

    /** choices[0].message of a non-streamed reply. */
    private fun parseMessage(raw: String): ChatResult {
        val json = runCatching { JsonParse.parseObject(raw) }.getOrNull()
            ?: throw LlmError("bad LLM reply: " + raw.take(200))
        checkError(json)
        val choice = json["choices"]?.arr?.firstOrNull()?.obj
        val msg = choice?.get("message")?.obj ?: throw LlmError("bad LLM reply: " + raw.take(200))
        val result = ChatResult(text = msg["content"]?.str ?: "")
        note(json, choice, result)
        result.reasoningChars = reasoning(msg).length
        result.rawChars = result.text.length
        if (result.text.isNotEmpty() || result.reasoningChars > 0) onProgress?.invoke(Progress.RECEIVING)
        return result
    }

    /** Providers can report errors inside an HTTP 200 stream or JSON response. */
    private fun checkError(json: JObject) {
        val error = json["error"] ?: return
        val message = (error as? JValue.ObjV)?.v?.get("message")?.str ?: error.str ?: "Provider error"
        throw LlmError(message.take(300))
    }

    /** finish_reason and usage, from whichever event carries them. */
    private fun note(json: JObject, choice: JObject?, result: ChatResult) {
        choice?.get("finish_reason")?.str?.let { result.finishReason = it }
        (json["usage"] as? JValue.ObjV)?.v?.let { u ->
            result.usage = u.pairs.mapNotNull { (k, v) -> v.int?.let { k to it } }.toMap()
        }
    }

    /** Reasoning text (vLLM/DeepSeek `reasoning_content`, others `reasoning`). */
    private fun reasoning(part: JObject?): String =
        part?.get("reasoning_content")?.str ?: part?.get("reasoning")?.str ?: ""
}
