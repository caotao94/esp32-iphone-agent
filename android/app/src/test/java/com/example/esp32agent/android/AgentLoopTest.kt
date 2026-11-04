package com.example.esp32agent.android

import com.example.esp32agent.android.agent.AgentLoop
import com.example.esp32agent.android.agent.Prompts
import com.example.esp32agent.android.board.BoardTimeout
import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.gestures.Gestures
import com.example.esp32agent.android.net.ChatModel
import com.example.esp32agent.android.net.ChatResult
import com.example.esp32agent.android.net.Progress
import com.example.esp32agent.android.screen.ScreenSnapshot
import com.example.esp32agent.android.screen.ScreenSource
import com.example.esp32agent.android.util.Pt
import com.example.esp32agent.android.util.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise complete runs: assertions count gestures that would physically reach the phone. */
class AgentLoopTest {
    @Test
    fun aBatchedReplyPerformsOnlyItsFirstActionThenLooksAgain() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 3))
        val phone = FakeGestures()
        val actionsBeforeEachLook = mutableListOf<Int>()
        val actor = FakeModel(
            replies = listOf(
                "DO: tap 100 200\nDO: tap 900 900",
                "DO: tap 700 600",
                "DO: done finished",
            ),
            beforeReply = { actionsBeforeEachLook += phone.calls.size },
        )
        val loop = loop(this, screen, phone, actor)

        loop.start("Open two controls")
        loop.join()

        assertEquals(listOf("tap", "tap"), phone.calls.map { it.name })
        assertEquals(listOf(Pt(0.1, 0.2), Pt(0.7, 0.6)), phone.calls.map { it.points.single() })
        assertEquals(listOf(0, 1, 2), actionsBeforeEachLook)
        assertEquals(listOf(1, 2, 3), actor.calls.map { it.frameId })
        assertEquals(2, screen.afterActionCalls)
        assertEquals(listOf("plan", "tap", "tap", "done"), loop.actionNames())
        assertFalse(loop.running.value)
    }

    @Test
    fun aTapWithoutVisibleChangeCanBeFollowedByTyping() = runTest {
        val screen = FakeScreen(afterFrames = listOf(1, 2))
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = listOf("DO: tap 200 400", "DO: type hello@example.com", "DO: done entered"),
        )
        val loop = loop(this, screen, phone, actor)

        loop.start("Enter an email in the visible field")
        loop.join()

        assertEquals(listOf("tap", "type"), phone.calls.map { it.name })
        assertEquals("hello@example.com", phone.calls[1].text)
        assertEquals(false, loop.steps.value.single { it["action"]?.str == "tap" }["changed"]?.bool)
        assertTrue(actor.calls[1].text.contains("screen did NOT change"))
        assertEquals(listOf(1, 1, 2), actor.calls.map { it.frameId })
        assertEquals(listOf("plan", "tap", "type", "done"), loop.actionNames())
    }

    @Test
    fun aNearDuplicateTapAfterNoChangeNeverReachesThePhoneTwice() = runTest {
        val screen = FakeScreen()
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = listOf(
                "DO: tap 500 600",
                "DO: tap 515 590",
                "DO: tap 490 610",
                "DO: tap 505 595",
            ),
        )
        val loop = loop(this, screen, phone, actor)

        loop.start("Open the control")
        loop.join()

        assertEquals(1, phone.calls.size)
        assertEquals("tap", phone.calls.single().name)
        assertEquals(1, screen.afterActionCalls)
        assertEquals(4, actor.calls.size)
        assertTrue(actor.calls[2].text.contains("Do not click the same control again"))
        assertEquals(listOf("plan", "tap", "fail"), loop.actionNames())
        assertTrue(loop.steps.value.last()["reason"]?.str.orEmpty().contains("no further clicks"))
    }

    @Test
    fun repeatedReplanningStopsBeforeTheStepLimit() = runTest {
        val screen = FakeScreen()
        val phone = FakeGestures()
        val actor = FakeModel(fallback = "DO: replan")
        val planner = FakeModel(fallback = "Use a different visible control")
        val loop = loop(this, screen, phone, actor, planner)

        loop.start("Find a way forward")
        loop.join()

        assertTrue(phone.calls.isEmpty())
        assertEquals(0, screen.afterActionCalls)
        assertEquals(3, actor.calls.size)
        assertEquals(3, planner.calls.size)
        assertEquals("fail", loop.actionNames().last())
        val reason = loop.steps.value.last()["reason"]?.str.orEmpty()
        assertTrue(reason.contains("replanning repeatedly"))
        assertFalse(reason.contains("step limit"))
        assertFalse(loop.running.value)
    }

    @Test
    fun aLayoutChangeWhileThinkingDiscardsTheOldCoordinates() = runTest {
        val screen = FakeScreen(afterFrames = listOf(3))
        val phone = FakeGestures()
        var replies = 0
        val actor = FakeModel(
            replies = listOf("DO: tap 100 200", "DO: tap 700 600", "DO: done finished"),
            beforeReply = {
                if (replies++ == 0) screen.publish(2)
            },
        )
        // Keep the layout mutation on the actor request; the initial planner look must remain frame 1.
        val loop = loop(this, screen, phone, actor, FakeModel(fallback = "Tap the visible control"))

        loop.start("Tap the control after the page loads")
        loop.join()

        assertEquals(1, phone.calls.size)
        assertEquals(listOf(Pt(0.7, 0.6)), phone.calls.single().points)
        assertEquals(listOf(1, 2, 3), actor.calls.map { it.frameId })
        assertEquals(1, screen.settledCalls)
        assertEquals(1, screen.afterActionCalls)
        assertEquals(listOf("plan", "stale", "tap", "done"), loop.actionNames())
    }

    @Test
    fun repeatedSwipesAreAllowedWhileTheScreenKeepsChanging() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5))
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = List(4) { "DO: swipe 500 800 500 200" } + "DO: done found it",
        )
        val planner = FakeModel(fallback = "Scroll until the item is visible")
        val loop = loop(this, screen, phone, actor, planner)

        loop.start("Find an item further down the list")
        loop.join()

        assertEquals(List(4) { "swipe" }, phone.calls.map { it.name })
        assertEquals(listOf(1, 2, 3, 4, 5), actor.calls.map { it.frameId })
        assertEquals(4, screen.afterActionCalls)
        assertEquals(1, planner.calls.size)
        val swipes = loop.steps.value.filter { it["action"]?.str == "swipe" }
        assertEquals(4, swipes.size)
        assertTrue(swipes.all { it["changed"]?.bool == true })
        assertEquals("done", loop.actionNames().last())
    }

    @Test
    fun alternatingToggleStatesStopBeforeTheFifthPhysicalTap() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 1, 2, 1))
        val phone = FakeGestures()
        val actor = FakeModel(fallback = "DO: tap 500 600")
        // Every tap changes pixels, but the same two content states keep returning.
        val loop = loop(this, screen, phone, actor, sceneChanged = { _, _, _ -> false })

        loop.start("Enable the control")
        loop.join()

        assertEquals(List(4) { "tap" }, phone.calls.map { it.name })
        assertEquals(listOf(1, 2, 1, 2, 1), actor.calls.map { it.frameId })
        assertEquals(4, screen.afterActionCalls)
        val taps = loop.steps.value.filter { it["action"]?.str == "tap" }
        assertEquals(4, taps.size)
        assertTrue(taps.all { it["changed"]?.bool == true })
        assertEquals(listOf("plan", "tap", "tap", "tap", "tap", "fail"), loop.actionNames())
        assertTrue(loop.steps.value.last()["reason"]?.str.orEmpty().contains("already been tried twice"))
    }

    @Test
    fun repeatedTapsAreAllowedWhileVisibleContentChangesOnTheSameLayout() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5))
        val phone = FakeGestures()
        val actor = FakeModel(replies = List(4) { "DO: tap 500 600" } + "DO: done reached five")
        val planner = FakeModel(fallback = "Increase the visible counter four times")
        val loop = loop(this, screen, phone, actor, planner, sceneChanged = { _, _, _ -> false })

        loop.start("Increase the counter from one to five")
        loop.join()

        assertEquals(List(4) { "tap" }, phone.calls.map { it.name })
        assertTrue(phone.calls.all { it.points == listOf(Pt(0.5, 0.6)) })
        assertEquals(listOf(1, 2, 3, 4, 5), actor.calls.map { it.frameId })
        assertEquals(4, screen.afterActionCalls)
        assertEquals(1, planner.calls.size)
        assertEquals(listOf("plan", "tap", "tap", "tap", "tap", "done"), loop.actionNames())
        assertTrue(loop.steps.value.filter { it["action"]?.str == "tap" }.all { it["changed"]?.bool == true })
    }

    @Test
    fun aRoundTripBetweenTheSameScreensIsCaughtAndTheActorIsWarned() = runTest {
        // The shade's ticking clock changes pixels on every frame, but the layout keeps alternating:
        // odd frames are the home screen, even frames the notification shade.
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5))
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = listOf(
                "DO: swipe 500 10 500 600",
                "DO: home",
                "DO: swipe 500 10 500 600",
                "DO: home",
                "DO: tap 703 194",
                "DO: done opened the search",
            ),
        )
        val loop = loop(this, screen, phone, actor, sceneChanged = { a, b, _ -> layout(a) != layout(b) })

        loop.start("Open the app search")
        loop.join()

        assertEquals(listOf("swipe", "home", "swipe", "tap"), phone.calls.map { it.name })
        assertEquals(6, actor.calls.size)
        assertFalse(actor.calls[3].text.contains("back and forth"))
        assertTrue(actor.calls[4].text.contains("back and forth"))
        assertEquals(listOf("plan", "swipe", "home", "swipe", "tap", "done"), loop.actionNames())
    }

    @Test
    fun anActorThatKeepsGoingBackAndForthIsStopped() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5, 6))
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = listOf(
                "DO: swipe 500 10 500 600",
                "DO: home",
                "DO: swipe 500 10 500 600",
                "DO: home",  // round trip 1: warned
                "DO: home",  // insists: performed
                "DO: swipe 500 10 500 600",  // round trip 2: warned
                "DO: swipe 500 10 500 600",  // insists: performed
                "DO: home",  // round trip 3: stopped
            ),
        )
        val loop = loop(this, screen, phone, actor, sceneChanged = { a, b, _ -> layout(a) != layout(b) })

        loop.start("Open the app search")
        loop.join()

        assertEquals(listOf("swipe", "home", "swipe", "home", "swipe"), phone.calls.map { it.name })
        assertEquals(8, actor.calls.size)
        assertEquals("fail", loop.actionNames().last())
        assertTrue(loop.steps.value.last()["reason"]?.str.orEmpty().contains("back and forth"))
        assertFalse(loop.running.value)
    }

    @Test
    fun openingDifferentItemsFromTheSameListIsNotARoundTrip() = runTest {
        // Back returns to the same list layout every time, but each round opens another item.
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5, 6, 7))
        val phone = FakeGestures()
        val actor = FakeModel(
            replies = listOf(
                "DO: tap 500 300", "DO: back",
                "DO: tap 500 450", "DO: back",
                "DO: tap 500 600", "DO: back",
                "DO: done read three items",
            ),
        )
        val loop = loop(this, screen, phone, actor, sceneChanged = { a, b, _ -> layout(a) != layout(b) })

        loop.start("Read the first three items")
        loop.join()

        assertEquals(listOf("tap", "back", "tap", "back", "tap", "back"), phone.calls.map { it.name })
        assertTrue(actor.calls.none { it.text.contains("back and forth") })
        assertEquals("done", loop.actionNames().last())
    }

    @Test
    fun aWaitIsPerformedEvenWhenThePageChangesWhileThinking() = runTest {
        val screen = FakeScreen(afterFrames = listOf(3))
        val phone = FakeGestures()
        var replies = 0
        val actor = FakeModel(
            replies = listOf("DO: wait", "DO: done loaded"),
            beforeReply = { if (replies++ == 0) screen.publish(2) },  // the page loads during the request
        )
        val loop = loop(this, screen, phone, actor, FakeModel(fallback = "Wait for the page to load"))

        loop.start("Wait for the results")
        loop.join()

        assertEquals(listOf("plan", "wait", "done"), loop.actionNames())
        assertEquals(1, screen.afterActionCalls)
        assertEquals(0, screen.settledCalls)
    }

    @Test
    fun aTimedOutTapIsRecordedAsUnknownAndNotReplayedAfterReplanning() = runTest {
        val screen = FakeScreen()
        // Record the physical tap before losing its acknowledgement.
        val phone = FakeGestures(afterTap = { throw BoardTimeout("tap", 5.0) })
        val actor = FakeModel(fallback = "DO: tap 500 600")
        val planner = FakeModel(fallback = "Inspect the current screen before choosing another control")
        val loop = loop(this, screen, phone, actor, planner)

        loop.start("Open the control once")
        loop.join()

        assertEquals(1, phone.calls.size)
        assertEquals("tap", phone.calls.single().name)
        assertEquals(0, screen.afterActionCalls)
        assertEquals(1, screen.settledCalls)
        assertEquals(2, planner.calls.size)
        assertEquals(4, actor.calls.size)
        val tap = loop.steps.value.single { it["action"]?.str == "tap" }
        assertEquals("unknown", tap["outcome"]?.str)
        assertTrue(tap["reason"]?.str.orEmpty().contains("may already have been applied"))
        assertTrue(planner.calls[1].text.contains("\"outcome\": \"unknown\""))
        assertTrue(actor.calls[1].text.contains("\"outcome\": \"unknown\""))
        assertEquals(listOf("plan", "tap", "uncertain", "replan (stuck)", "fail"), loop.actionNames())
        assertTrue(loop.steps.value.last()["reason"]?.str.orEmpty().contains("no further clicks"))
    }

    @Test
    fun theSameTapCoordinatesAreAllowedOnDifferentScenes() = runTest {
        val screen = FakeScreen(afterFrames = listOf(2, 3, 4, 5))
        val phone = FakeGestures()
        val actor = FakeModel(replies = List(4) { "DO: tap 500 600" } + "DO: done finished")
        val planner = FakeModel(fallback = "Advance through the successive screens")
        val loop = loop(this, screen, phone, actor, planner)

        loop.start("Advance through four screens with the button in the same position")
        loop.join()

        assertEquals(List(4) { "tap" }, phone.calls.map { it.name })
        assertTrue(phone.calls.all { it.points == listOf(Pt(0.5, 0.6)) })
        assertEquals(listOf(1, 2, 3, 4, 5), actor.calls.map { it.frameId })
        assertEquals(4, screen.afterActionCalls)
        assertEquals(1, planner.calls.size)
        assertEquals(listOf("plan", "tap", "tap", "tap", "tap", "done"), loop.actionNames())
        assertTrue(loop.steps.value.filter { it["action"]?.str == "tap" }.all { it["changed"]?.bool == true })
    }

    private fun loop(
        scope: CoroutineScope,
        screen: FakeScreen,
        phone: FakeGestures,
        actor: FakeModel,
        planner: FakeModel = FakeModel(fallback = "Follow the visible controls"),
        sceneChanged: (ByteArray?, ByteArray?, List<Pt>) -> Boolean = { before, after, _ -> !sameFrame(before, after) },
    ) = AgentLoop(
        scope = scope,
        phone = phone,
        screen = screen,
        actor = actor,
        planner = planner,
        trace = null,
        prompts = Prompts("planner rules", "actor rules", "phone rules"),
        maxSteps = 30,
        frameChanged = { before, after, _ -> !sameFrame(before, after) },
        sceneChanged = sceneChanged,
    )

    private fun AgentLoop.actionNames(): List<String?> = steps.value.map { it["action"]?.str }

    private data class ModelCall(val text: String, val frameId: Int?)

    private class FakeModel(
        private val replies: List<String> = emptyList(),
        private val fallback: String? = null,
        private val beforeReply: suspend () -> Unit = {},
    ) : ChatModel {
        override val model = "test-model"
        override var onProgress: ((Progress) -> Unit)? = null
        val calls = mutableListOf<ModelCall>()

        override suspend fun chat(
            system: String,
            text: String,
            image: ByteArray?,
            maxTokens: Int,
            temperature: Double,
        ): ChatResult {
            val index = calls.size
            calls += ModelCall(text, image?.single()?.toInt())
            beforeReply()
            return ChatResult(replies.getOrNull(index) ?: fallback ?: error("Unexpected model request ${index + 1}"))
        }
    }

    /** Byte IDs stand in for JPEGs; explicit timestamps avoid waiting on the wall clock. */
    private class FakeScreen(private val afterFrames: List<Int> = emptyList()) : ScreenSource {
        private var latest = ScreenSnapshot(byteArrayOf(1), now(), now())
        override val live = true
        override val frame: ByteArray? get() = latest.frame
        override val updated: Double get() = latest.updated
        override val capturedAt: Double get() = latest.capturedAt
        override val snapshot: ScreenSnapshot get() = latest
        override val aspect = 9.0 / 19.5
        var afterActionCalls = 0
            private set
        var settledCalls = 0
            private set

        fun publish(id: Int, timestamp: Double = latest.capturedAt + 1) {
            latest = ScreenSnapshot(byteArrayOf(id.toByte()), timestamp, timestamp)
        }

        override suspend fun afterAction(since: Double) {
            val next = afterFrames.getOrNull(afterActionCalls) ?: frame!!.single().toInt()
            afterActionCalls++
            publish(next, maxOf(since, latest.capturedAt) + 1)
        }

        override suspend fun settled(quiet: Double, least: Double, most: Double) {
            settledCalls++
        }
    }

    private data class GestureCall(val name: String, val points: List<Pt> = emptyList(), val text: String? = null)

    private class FakeGestures(private val afterTap: suspend () -> Unit = {}) : Gestures {
        override val device = object : HidDevice {
            override val links = 1
            override val connected = true
            override suspend fun send(command: String) = ""
            override suspend fun waitConnected(timeout: Double) = true
        }
        override val ready = true
        override val quality: Double? = null
        override val exact = true
        val calls = mutableListOf<GestureCall>()

        override suspend fun tap(x: Double, y: Double, aim: Boolean) {
            calls += GestureCall("tap", listOf(Pt(x, y)))
            afterTap()
        }

        override suspend fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, hold: Boolean) {
            calls += GestureCall("swipe", listOf(Pt(x1, y1), Pt(x2, y2)))
        }

        override suspend fun drag(points: List<Pt>, hold: Boolean) {
            calls += GestureCall("drag", points)
        }

        override suspend fun type(text: String) {
            calls += GestureCall("type", text = text)
        }

        override suspend fun key(combo: String) {
            calls += GestureCall("key", text = combo)
        }

        override suspend fun goHome() {
            calls += GestureCall("home")
        }

        override suspend fun goBack() {
            calls += GestureCall("back")
        }

        override suspend fun reset() = Unit
    }

    private companion object {
        fun sameFrame(before: ByteArray?, after: ByteArray?): Boolean = when {
            before == null -> after == null
            after == null -> false
            else -> before.contentEquals(after)
        }

        /** Two layouts for the round-trip tests: odd frame ids are one screen, even ids the other. */
        fun layout(frame: ByteArray?): Int? = frame?.single()?.toInt()?.rem(2)
    }
}
