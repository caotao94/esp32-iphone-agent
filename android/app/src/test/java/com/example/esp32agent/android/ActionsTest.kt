package com.example.esp32agent.android

import com.example.esp32agent.android.agent.Actions
import com.example.esp32agent.android.agent.PyJSON
import com.example.esp32agent.android.agent.j
import com.example.esp32agent.android.agent.jObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The grammar the prompts promise the model (prompts/actor.md) has to survive the port. */
class ActionsTest {
    @Test
    fun parsesCoordinates() {
        val a = Actions.parseCommand("tap 152 362")
        assertEquals("tap", a["action"]?.str)
        assertEquals(152L, a["x"]?.int)
        assertEquals(362L, a["y"]?.int)
        assertNull(a["precise"])
    }

    @Test
    fun parsesPreciseTap() {
        assertEquals(true, Actions.parseCommand("tap 10 20 precise")["precise"]?.bool)
    }

    @Test
    fun parsesTextArguments() {
        assertEquals("hello world", Actions.parseCommand("type hello world")["text"]?.str)
        assertEquals("cmd+a", Actions.parseCommand("key cmd+a")["combo"]?.str)
        assertEquals("all done", Actions.parseCommand("done all done")["answer"]?.str)
        assertEquals("8 AM", Actions.parseCommand("type \"8 AM\"")["text"]?.str)
    }

    @Test
    fun parsesArgumentFreeActions() {
        for (name in listOf("home", "back", "wait", "replan")) {
            assertEquals(name, Actions.parseCommand(name)["action"]?.str)
        }
    }

    @Test
    fun parsesSwipesAndPaths() {
        val s = Actions.parseCommand("swipe 100 200 300 400")
        assertEquals(listOf("x1", "y1", "x2", "y2"), s.keys.drop(1))
        assertEquals(400L, s["y2"]?.int)

        val p = Actions.parseCommand("path 1 2 3 4 5 6")
        assertEquals(3, p["points"]?.arr?.size)
        assertEquals(5L, p["points"]?.arr?.get(2)?.arr?.get(0)?.int)
    }

    @Test
    fun rejectsBadLines() {
        for (line in listOf("dance 1 2", "tap 1", "path 1 2 3", "swipe 1 2 3", "tap -1 500", "tap 1080 2400", "path 1 2 3 1001")) {
            try {
                Actions.parseCommand(line)
                fail("should not parse: " + line)
            } catch (expected: Actions.ParseError) {
                assertTrue(expected.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun parsesAWholeReply() {
        val acts = Actions.parseActions(
            """
            THINK: The Settings icon is visible, so I tap it.
            ZH: 我看到设置图标，点它。
            DO: tap 152 362
            DO: wait
            """.trimIndent()
        )
        assertEquals(2, acts.size)
        assertEquals("The Settings icon is visible, so I tap it.", acts[0]["thought"]?.str)
        assertEquals("我看到设置图标，点它。", acts[0]["thought_zh"]?.str)
        assertEquals(152L, acts[0]["x"]?.int)
        assertEquals("wait", acts[1]["action"]?.str)
        assertNull(acts[1]["thought"])
    }

    @Test
    fun tapRetryGuardAllowsOnlyMeaningfullyDifferentCoordinates() {
        val first = Actions.parseCommand("tap 500 500")
        assertTrue(Actions.sameTap(first, Actions.parseCommand("tap 525 520")))
        assertTrue(Actions.sameForRetry(first, Actions.parseCommand("tap 525 520")))
        assertTrue(!Actions.sameTap(first, Actions.parseCommand("tap 560 500")))
        assertTrue(!Actions.sameForRetry(first, Actions.parseCommand("swipe 500 500 500 700")))
    }

    @Test
    fun anUncertainOutcomeAndPrecisionHintDoNotChangeTheActionIdentity() {
        val first = Actions.parseCommand("tap 500 500")
        val recorded = Actions.parseCommand("tap 500 500 precise")
        recorded["outcome"] = "unknown".j()
        recorded["reason"] = "reply was lost".j()
        recorded["observed_after_action"] = false.j()
        assertEquals(Actions.key(first), Actions.key(recorded))
    }

    @Test
    fun rejectsAReplyWithNoAction() {
        try {
            Actions.parseActions("THINK: nothing to do")
            fail("should not parse")
        } catch (expected: Actions.ParseError) {
        }
    }

    @Test
    fun capsActionsAtSix() {
        val text = (1..9).joinToString("\n") { "DO: wait" }
        assertEquals(6, Actions.parseActions(text).size)
    }

    @Test
    fun parsesPlans() {
        val (en, zh) = Actions.parsePlan("EN: 1. Open Clock\nZH: 1. 打开时钟")
        assertEquals("1. Open Clock", en)
        assertEquals("1. 打开时钟", zh)
        val (both, bothZh) = Actions.parsePlan("1. Open Clock")
        assertEquals("1. Open Clock", both)
        assertEquals(both, bothZh)
    }

    @Test
    fun historyLinesReadLikePythonDicts() {
        val step = jObj(
            "action" to "tap".j(), "x" to 152.j(), "y" to 362.j(),
            "thought" to "look".j(), "t" to 1.5.j(),
        )
        assertEquals("""{"action": "tap", "x": 152, "y": 362}""", PyJSON.dumps(Actions.key(step)))
        assertEquals("""{"t": 1.5}""", PyJSON.dumps(jObj("t" to 1.5.j())))
        assertEquals("""{"s": "\u65e9\u4e0a"}""", PyJSON.dumps(jObj("s" to "早上".j())))
    }
}
