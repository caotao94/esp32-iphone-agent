package com.example.esp32agent.android

import com.example.esp32agent.android.agent.JValue
import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.board.LlmError
import com.example.esp32agent.android.net.LlmClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class LlmClientTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        })
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun startServer() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun stopServer() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun model(
        host: String = "ws-test.cn-beijing.maas.aliyuncs.com",
        id: String = "qwen3.8-flash",
        extra: Map<String, JValue> = emptyMap(),
        apiKey: String = "test-key",
    ) = LlmClient(
        baseURL = server.url("/compatible-mode/v1").newBuilder().host(host).build().toString(),
        model = id,
        apiKey = apiKey,
        extra = extra,
        client = client,
    )

    private fun enqueueStream() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )
    }

    @Test
    fun pastedWhitespaceIsRemovedBeforeChatAuthorizationIsBuilt() = runBlocking {
        enqueueStream()
        val endpoint = model(apiKey = "\uFEFF \t test-\r\nkey\u200B\u00A0\n")
        assertEquals("ok", endpoint.chat("Reply ok", "Check", null, 128, 0.0).text)
        assertEquals("Bearer test-key", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun pastedWhitespaceIsRemovedFromModelListAuthorizationToo() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-model"}]}"""))
        assertEquals(listOf("test-model"), model(apiKey = " test-key\r\n").models())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/compatible-mode/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test
    fun whitespaceOnlyKeyDoesNotSendAnAuthorizationHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        assertEquals(emptyList<String>(), model(apiKey = " \r\n\t").models())
        assertNull(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test
    fun unsupportedKeyCharactersAreRejectedWithoutDisclosingTheSecret() {
        for (badCharacter in listOf('\u0000', '\u007F', '\uFF0D')) {
            val error = assertThrows(LlmError::class.java) {
                runBlocking { model(apiKey = "private-test-key$badCharacter").models() }
            }
            assertEquals("API key contains unsupported characters. Paste only the key from your provider.", error.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aliyunQwenFlashReturnsContentWithoutSpendingTheTestBudgetOnThinking() = runBlocking {
        enqueueStream()
        val result = model().chat("Reply ok", "Check", null, 128, 0.0)
        assertEquals("ok", result.text)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/compatible-mode/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JsonParse.parseObject(request.body.readUtf8())!!
        assertEquals("qwen3.8-flash", body["model"]?.str)
        assertEquals(false, body["enable_thinking"]?.bool)
        assertEquals(128L, body["max_tokens"]?.int)
        assertEquals(true, body["stream"]?.bool)
    }

    @Test
    fun providerSpecificOptionsAreNotSentToOtherHostsOrOtherModels() = runBlocking {
        for (endpoint in listOf(model(host = "gateway.example.com"), model(id = "qwen3.8-2.4t-a95b"))) {
            enqueueStream()
            assertEquals("ok", endpoint.chat("Reply ok", "Check", null, 128, 0.0).text)
            val body = JsonParse.parseObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())!!
            assertNull(body["enable_thinking"])
        }
    }

    @Test
    fun explicitlyConfiguredThinkingOverridesTheProviderDefault() = runBlocking {
        enqueueStream()
        model(extra = mapOf("enable_thinking" to true.j())).chat("Reply ok", "Check", null, 128, 0.0)
        val body = JsonParse.parseObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())!!
        assertEquals(true, body["enable_thinking"]?.bool)
    }

    @Test
    fun visionRequestsStillContainImageDataAndSupportNonStreamingReplies() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"DO: home"},"finish_reason":"stop"}]}"""
            )
        )
        val result = model().chat("Choose an action", "Look", byteArrayOf(1, 2, 3), 1000, 0.0)
        assertEquals("DO: home", result.text)
        val body = JsonParse.parseObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())!!
        val content = body["messages"]!!.arr!![1].obj!!["content"]!!.arr!!
        assertEquals("image_url", content[0].obj!!["type"]?.str)
        assertEquals("data:image/jpeg;base64,AQID", content[0].obj!!["image_url"]?.obj?.get("url")?.str)
        assertFalse(result.text.isBlank())
    }
}
