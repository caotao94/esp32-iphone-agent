package com.example.esp32agent.android.board

import com.example.esp32agent.android.util.DebugLog
import com.example.esp32agent.android.util.now
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Where command lines go: the BLE link, or a fake board in tests. Port of BoardTransport in BoardCommands.swift. */
interface BoardTransport {
    val isConnected: Boolean

    @Throws(DeviceError::class)
    fun write(line: String)

    /** Fire-and-forget: write only if the link can take it right now; false = dropped. */
    fun writeIfIdle(line: String): Boolean = runCatching { write(line) }.isSuccess

    /** Bytes one write can carry without a response. */
    val maxWrite: Int get() = 180

    /** Drop the link after a timeout; the transport reconnects on its own (like the old TCP close). */
    fun dropLink()
}

/** What gestures need from the board. Port of HIDDevice in BoardCommands.swift. */
interface HidDevice {
    /** Bumped on every (re)connect, so gestures know to release and re-home. */
    val links: Int
    val connected: Boolean

    @Throws(DeviceError::class, BoardTimeout::class)
    suspend fun send(command: String): String

    suspend fun waitConnected(timeout: Double): Boolean
}

/**
 * Sends the board one command at a time and matches replies by request id.
 * Port of CommandChannel in BoardCommands.swift, which ports the legacy server's device.py.
 */
class CommandChannel(var transport: BoardTransport? = null) : HidDevice {
    private val mutex = Mutex()
    private val pending = HashMap<Int, CompletableDeferred<BoardReply>>()

    // The board serves two centrals (this app and a Mac harness) and notifies replies to both: start our ids
    // far from the harness's small ones, and drop replies to ids we did not send.
    private var nextId = (100_000..800_000).random()
    private var logTimes = ArrayList<Double>()

    private var linkCount = 0
    override val links: Int get() = linkCount
    var last = 0.0
        private set   // the last real command: the phone's idle time
    var beat = 0.0
        private set   // the last reply of any kind

    /** "app <command>" lines relayed from the Mac harness (hardware/tools/board_test.py). */
    var onAppCommand: ((String) -> Unit)? = null

    /** Command name and how long the board took, in ms. */
    var onTiming: ((String, Double) -> Unit)? = null

    /** Called once when a command times out; the caller can free the link and reconnect. */
    var onTimeout: (suspend (String) -> Unit)? = null

    var log: (String) -> Unit = { DebugLog.write(it) }

    override val connected: Boolean get() = transport?.isConnected == true

    /** The transport (re)connected and subscribed. */
    fun linkUp() {
        linkCount++
        last = now()
        beat = last  // fresh link: nothing to keep awake yet
    }

    /** The link dropped: fail everything in flight. */
    fun linkDown() {
        val waiting = ArrayList(pending.values)
        pending.clear()
        waiting.forEach { it.completeExceptionally(DeviceError("board disconnected")) }
    }

    fun handleReply(line: String) {
        val text = line.trim()
        if (text.startsWith("app ")) {
            onAppCommand?.invoke(text.removePrefix("app "))
            return
        }
        if (text.startsWith("log ")) return  // our own log lines, relayed to the harness
        val reply = BoardReply.parse(line) ?: return
        pending.remove(reply.id)?.complete(reply)
    }

    override suspend fun waitConnected(timeout: Double): Boolean {
        val deadline = now() + timeout
        while (!connected && now() < deadline) delay(200)
        return connected
    }

    /** Send one command; returns the text after "ok" (e.g. bench's ms). Throws on `err`, timeout or a lost link. */
    override suspend fun send(command: String): String = try {
        sendOnce(command, null)
    } catch (e: BoardTimeout) {
        // A HID command may have reached the board even when its reply was lost. Retrying tap, swipe,
        // click, type, key, Home or Back would perform the action twice. Let the caller observe the
        // current screen and decide what to do after the link recovers.
        onTimeout?.invoke(command)
        throw e
    }

    /**
     * A timeout is the board being slow, not a lost link: it throws [BoardTimeout] and keeps the connection;
     * only a real disconnect fails as a lost link.
     */
    private suspend fun sendOnce(command: String, custom: Double?): String {
        val t = transport ?: throw DeviceError("board not connected")
        if (!t.isConnected) throw DeviceError("board not connected")
        val timeout = custom ?: timeoutFor(command)
        val name = command.substringBefore(' ')
        return mutex.withLock {
            val started = now()
            val id = nextId++
            val deferred = CompletableDeferred<BoardReply>()
            pending[id] = deferred
            try {
                t.write("$id $command")
            } catch (e: Exception) {
                pending.remove(id)
                throw DeviceError("board link lost during '${command.take(30)}': ${e.message}")
            }
            val reply = try {
                withTimeout((timeout * 1000).toLong()) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                pending.remove(id)
                DebugLog.write("cmd #$id ${command.take(40)} -> timeout after " + ((now() - started) * 1000).toInt() + " ms")
                throw BoardTimeout(command.take(30), timeout)
            } catch (e: CancellationException) {
                pending.remove(id)
                throw e
            } catch (e: DeviceError) {
                DebugLog.write("cmd #$id ${command.take(40)} -> link lost after " + ((now() - started) * 1000).toInt() + " ms")
                throw DeviceError("board link lost during '${command.take(30)}': ${e.message}")
            }
            beat = now()
            if (command != "ping") {
                last = beat
                onTiming?.invoke(name, (now() - started) * 1000)
            }
            if (name != "ping") {
                DebugLog.write(
                    "cmd #$id ${command.take(40)} -> " + (if (reply.ok) "ok" else "err") + " " + reply.text.take(60) +
                        " (" + ((now() - started) * 1000).toInt() + " ms)"
                )
            }
            if (!reply.ok) throw DeviceError("err " + reply.text)
            reply.text
        }
    }

    /**
     * Send a debug-log line to the Mac harness through the board (`log <text>`): fire-and-forget, at most 10 a
     * second, truncated to one write, dropped when the link is busy.
     */
    fun relayLog(text: String): Boolean {
        val t = transport ?: return false
        if (!t.isConnected) return false
        val now = now()
        logTimes = ArrayList(logTimes.filter { now - it < 1.0 })
        if (logTimes.size >= 10) return false
        val prefix = "$nextId log "
        nextId++
        var body = text.replace('\n', ' ')
        val room = t.maxWrite - prefix.toByteArray().size
        while (body.toByteArray().size > room && body.isNotEmpty()) body = body.dropLast(1)
        if (!t.writeIfIdle(prefix + body)) return false
        logTimes.add(now)
        return true
    }

    /**
     * While idle: ping every few seconds, so a board that went away shows as gone within seconds, and nudge
     * the pointer now and then so the phone does not auto-lock (a HID report counts as user activity, which
     * is what keeps an Android screen on while the agent works in another app).
     */
    suspend fun keepAwake(idle: Double = 25.0, heartbeat: Double = 2.0, screenLive: () -> Boolean) {
        while (true) {
            delay((heartbeat * 1000).toLong())
            if (!connected || now() - beat < heartbeat) continue
            try {
                if (now() - last > idle && screenLive()) {
                    send("move 1 0")
                    send("move -1 0")
                } else {
                    send("ping")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("board gone: ${e.message}")
            }
        }
    }

    companion object {
        /**
         * Seconds the board may take: replies need up to ~2 s at p99, relative moves are paced (10 units per
         * 8 ms), an absolute swipe is paced too, typing is per character.
         */
        fun timeoutFor(command: String): Double {
            val parts = command.split(' ', limit = 2)
            val name = parts[0]
            val arg = if (parts.size > 1) parts[1] else ""
            return when (name) {
                "move" -> {
                    val biggest = arg.split(' ').mapNotNull { it.toLongOrNull() }.maxOfOrNull { kotlin.math.abs(it) } ?: 0L
                    5 + biggest / 10 * 0.012
                }
                // an absolute swipe is paced on the board: one report every 8 ms, plus the holds
                "swipe" -> 5 + (arg.split(' ').getOrNull(4)?.toLongOrNull() ?: 250L) / 1000.0 + 1.0
                "type" -> 5 + 0.1 * arg.length
                "ping" -> 8.0  // an idle check: tolerate a hiccup rather than drop a healthy board
                "snap" -> 15.0  // a whole GOP over BLE (not in an Android build)
                "bench" -> 5 + (arg.toDoubleOrNull() ?: 0.0) / 10  // KB at >= 80 kbps
                else -> 5.0
            }
        }
    }
}
