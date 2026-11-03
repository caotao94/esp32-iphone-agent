package com.example.esp32agent.android

import com.example.esp32agent.android.agent.JObject
import com.example.esp32agent.android.agent.JsonParse
import com.example.esp32agent.android.agent.JValue
import com.example.esp32agent.android.agent.PyJSON
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history sent to the model has to read exactly like the Python and Swift versions did (json.dumps key
 * order and separators), and the model's reply has to survive a parse/dump round trip.
 */
class JsonTest {
    @Test
    fun dumpsLikePython() {
        val step = jObj("action" to "tap".j(), "x" to 152.j(), "y" to 362.j(), "think_s" to 2.5.j())
        assertEquals("""{"action": "tap", "x": 152, "y": 362, "think_s": 2.5}""", PyJSON.dumps(step))
        assertEquals("""{"shot": true, "t": 1.0}""", PyJSON.dumps(jObj("shot" to true.j(), "t" to 1.0.j())))
        assertEquals("[1, 2, 3]", PyJSON.dumps(listOf<JValue>(1.j(), 2.j(), 3.j()).j()))
        assertEquals("null", PyJSON.dumps(JValue.NullV))
    }

    @Test
    fun keepsInsertionOrderAndOverwritesInPlace() {
        val o = jObj("a" to 1.j(), "b" to 2.j())
        o["a"] = 9.j()
        o["c"] = 3.j()
        assertEquals(listOf("a", "b", "c"), o.keys)
        assertEquals("""{"a": 9, "b": 2, "c": 3}""", PyJSON.dumps(o))
    }

    @Test
    fun mergingLetsTheOtherKeysWinButKeepsOrder() {
        val a = jObj("action" to "tap".j(), "x" to 1.j())
        val b = jObj("thought" to "look".j(), "x" to 2.j())
        val merged = a.merging(b)
        assertEquals("""{"action": "tap", "x": 2, "thought": "look"}""", PyJSON.dumps(merged))
    }

    @Test
    fun equalityIgnoresOrderLikePythonDicts() {
        assertEquals(jObj("a" to 1.j(), "b" to 2.j()), jObj("b" to 2.j(), "a" to 1.j()))
    }

    @Test
    fun escapesNonAsciiTheWayTheModelExpects() {
        assertEquals("""{"s": "\u65e9\u4e0a"}""", PyJSON.dumps(jObj("s" to "早上".j())))
        assertEquals("""{"s": "早上"}""", PyJSON.dumps(jObj("s" to "早上".j()), ascii = false))
        assertEquals("""{"s": "a\"b\\c\nd"}""", PyJSON.dumps(jObj("s" to "a\"b\\c\nd".j())))
    }

    @Test
    fun emojiUsesJsonSurrogateEscapesAndRoundTrips() {
        val value = jObj("s" to "😀".j())
        val encoded = PyJSON.dumps(value)
        assertEquals("""{"s": "\ud83d\ude00"}""", encoded)
        assertEquals(value, JsonParse.parseObject(encoded))
    }

    @Test
    fun parsesWhatTheBoardSends() {
        val status = """{"up":123456,"rst":"power-on","heap":81234,"usb":"ok","net":"none","ap":"idle",
            |"w":480,"h":1040,"kbps":180.5,"fps":4.8,"ble":{"mtu":247,"phy":2,"kbps":350,"drop":0,"n":1}}"""
            .trimMargin().replace("\n", "")
        val o = JsonParse.parseObject(status)!!
        assertEquals(123456L, o["up"]?.int)
        assertEquals("power-on", o["rst"]?.str)
        assertEquals(180.5, o["kbps"]?.double!!, 1e-9)
        assertEquals(247L, o["ble"]?.obj?.get("mtu")?.int)
    }

    @Test
    fun parsesTheModelReplyShapes() {
        val json = JsonParse.parseObject(
            """{"choices":[{"delta":{"content":"DO: tap 1 2"},"finish_reason":"stop"}],
               "usage":{"prompt_tokens":10,"completion_tokens":4}}"""
        )!!
        val choice = json["choices"]?.arr?.firstOrNull()?.obj
        assertEquals("DO: tap 1 2", choice?.get("delta")?.obj?.get("content")?.str)
        assertEquals("stop", choice?.get("finish_reason")?.str)
        assertEquals(10L, json["usage"]?.obj?.get("prompt_tokens")?.int)
    }

    @Test
    fun roundTripsAndRejectsGarbage() {
        val original = """{"a": [1, 2.5, true, null, "x"], "b": {"c": "d"}}"""
        assertEquals(original, PyJSON.dumps(JsonParse.parse(original)))
        for (bad in listOf("{", "{\"a\":}", "{\"a\" 1}", "[1,]", "nul", "{\"a\":1}x")) {
            val parsed = runCatching { JsonParse.parse(bad) }
            assertTrue("should not parse: $bad", parsed.isFailure)
        }
    }

    @Test
    fun intsStayIntsAndFloatsStayFloats() {
        assertEquals("""{"i": 3, "f": 3.5}""", PyJSON.dumps(JsonParse.parseObject("""{"i":3,"f":3.5}""")!!))
        assertNull(JsonParse.parseObject("""{"i":3}""")!!["f"])
    }
}
