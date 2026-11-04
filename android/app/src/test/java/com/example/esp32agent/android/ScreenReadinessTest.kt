package com.example.esp32agent.android

import com.example.esp32agent.android.screen.ScreenSource
import com.example.esp32agent.android.screen.awaitLiveFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenReadinessTest {
    private class FakeScreen : ScreenSource {
        override var live = false
        override var frame: ByteArray? = null
        override val updated = 0.0
        override val capturedAt = 0.0
        override val aspect = 0.5

        fun publish() {
            frame = byteArrayOf(1, 2, 3)
            live = true
        }
    }

    @Test
    fun waitsForTheAsynchronousFirstFrameInsteadOfFailingAtStartup() = runTest {
        val screen = FakeScreen()
        val ready = async { screen.awaitLiveFrame() }
        runCurrent()
        advanceTimeBy(250)
        assertFalse(ready.isCompleted)
        screen.publish()
        assertTrue(ready.await())
    }

    @Test
    fun anAlreadyLiveFrameStartsImmediately() = runTest {
        val screen = FakeScreen().apply { publish() }
        assertTrue(screen.awaitLiveFrame())
    }

    @Test
    fun permissionWithoutAnImageTimesOut() = runTest {
        assertFalse(FakeScreen().awaitLiveFrame(timeoutMillis = 500))
    }

    @Test
    fun anEmptyImageCannotStartTheAgent() = runTest {
        val screen = FakeScreen().apply { live = true; frame = byteArrayOf() }
        assertFalse(screen.awaitLiveFrame(timeoutMillis = 500))
    }

    @Test
    fun aStaleImageFromAnInactiveCaptureCannotStartTheAgent() = runTest {
        val screen = FakeScreen().apply { frame = byteArrayOf(1) }
        assertFalse(screen.awaitLiveFrame(timeoutMillis = 500))
    }

    @Test
    fun revokedCaptureEndsTheWaitBeforeTheTimeout() = runTest {
        val screen = FakeScreen()
        var capturing = true
        val ready = async { screen.awaitLiveFrame(capturing = { capturing }) }
        runCurrent()
        advanceTimeBy(100)
        capturing = false
        assertFalse(ready.await())
        assertTrue(testScheduler.currentTime < 10_000)
    }

    @Test
    fun stoppingTheRunCancelsTheWait() = runTest {
        val ready = async { FakeScreen().awaitLiveFrame() }
        runCurrent()
        ready.cancel()
        ready.join()
        assertTrue(ready.isCancelled)
    }
}
