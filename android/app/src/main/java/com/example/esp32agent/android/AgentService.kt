package com.example.esp32agent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.esp32agent.android.agent.AgentLoop
import com.example.esp32agent.android.agent.Recovery
import com.example.esp32agent.android.agent.StepText
import com.example.esp32agent.android.gestures.Gestures
import com.example.esp32agent.android.util.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns a run: the screen capture, the board link and the see-think-act loop, as a `mediaProjection`
 * foreground service, so the agent keeps working while another app is in front.
 *
 * Android 14+ wants this service running *before* the MediaProjection is created, and a fresh consent per
 * session, so the activity asks for the capture and then hands the result here ([start]).
 */
class AgentService : LifecycleService() {

    private var graph: AppGraph? = null
    private var gestures: Gestures? = null
    private var loop: AgentLoop? = null
    private var runJob: Job? = null
    private var notifier: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notifier = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopRun("stopped")
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK) ?: ""
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_RUN
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null || (mode == MODE_RUN && task.isEmpty())) {
            stopRun("nothing to do")
            return START_NOT_STICKY
        }
        // Foreground first: on Android 14 the projection may only be created by a running mediaProjection
        // service, and the type has to be declared at this call.
        val title = if (mode == MODE_CALIBRATE) "Calibrating the pointer" else task
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(title, "starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        AgentRuntime.reset()
        AgentRuntime.setRunning(true)
        startRun(task, resultCode, data, mode)
        return START_NOT_STICKY
    }

    private fun startRun(task: String, resultCode: Int, data: Intent, mode: String) {
        // One graph per process (AgentApp): the activity paired the board, this run uses the same link.
        val app = application as AgentApp
        val scope = app.scope
        val g = app.graph
        graph = g
        runJob = scope.launch {
            // 1. eyes: the phone's own screen, no hotspot and no mirrored video
            if (!g.capture.start(resultCode, data)) {
                failRun(g.capture.note.ifEmpty { "the screen capture did not start" })
                endRun()
                return@launch
            }
            // 2. hands: the board over BLE
            if (!g.board.commands.connected) {
                AgentRuntime.setNotice("connecting to the board...")
                g.board.reconnect()
                if (!g.board.commands.waitConnected(20.0)) {
                    failRun("the board did not connect: plug it in, unlock the phone, pair it in the app")
                    endRun()
                    return@launch
                }
            }
            if (mode == MODE_CALIBRATE) {
                calibrate(g)
                return@launch
            }
            // 3. brain
            val models = g.models()
            if (models == null) {
                failRun("no model set: enter an endpoint, a key and a vision model")
                endRun()
                return@launch
            }
            // 4. which pointer this board has
            val hands = try {
                g.gestures()
            } catch (e: Exception) {
                failRun("the board did not answer: " + (e.message ?: "the link is lost"))
                endRun()
                return@launch
            }
            gestures = hands
            if (!hands.ready) {
                failRun("the pointer needs calibrating first (Board > Calibrate pointer)")
                endRun()
                return@launch
            }
            // 5. go
            AgentRuntime.setNotice(null)
            g.trace.start(task)
            AgentRuntime.setRunDir(g.trace.dir?.absolutePath)
            val agent = AgentLoop(
                scope = scope,
                phone = hands,
                screen = g.capture,
                actor = models.first,
                planner = models.second,
                trace = g.trace,
                prompts = g.prompts,
                maxSteps = g.settings.maxSteps,
            )
            loop = agent
            wire(agent)
            try {
                agent.start(task, traceStarted = true)
                publish(agent)
                agent.join()
            } catch (e: Exception) {
                failRun(e.message ?: "the run failed")
            }
            publish(agent)
            endRun()
        }
    }

    /**
     * The relative-pointer calibration (a stock-firmware board): home the pointer, wiggle it at two known
     * distances and find it on screen. Needs the capture, so it runs here, in the service.
     */
    private suspend fun calibrate(g: AppGraph) {
        AgentRuntime.setNotice(null)
        val pointer = g.relGestures()
        gestures = pointer
        val outcome = runCatching {
            pointer.startCalibration { step ->
                AgentRuntime.setDoing(
                    when (step) {
                        0 -> "calibrating: homing the pointer"
                        else -> "calibrating: looking for the pointer ($step/${pointer.stopCount})"
                    }
                )
                notifyNow(AgentRuntime.doing.value)
            }
        }
        when {
            outcome.isFailure -> failRun("calibration failed: " + (outcome.exceptionOrNull()?.message ?: "the board did not answer"))
            outcome.getOrNull() == true -> {
                g.settings.pointerMode = "rel"
                AgentRuntime.setNotice("pointer calibrated")
            }
            else -> failRun("the pointer was not found on screen: keep the screen still and try again")
        }
        endRun()
    }

    private fun wire(agent: AgentLoop) {
        // The iPhone app pauses while its own UI is in front, so the agent never acts on itself; this does
        // the same, and MainActivity.moveTaskToBack is what puts the target app in front again.
        agent.gate = { while (uiInFront.value) delay(200) }
        agent.link = {
            Recovery.Link(
                connected = graph?.board?.commands?.connected == true,
                capturing = graph?.capture?.live == true,
                frameAt = graph?.capture?.updated ?: 0.0,
            )
        }
        agent.onDoing = { doing ->
            val text = when (doing) {
                is AgentLoop.Doing.Looking -> "looking at the screen"
                is AgentLoop.Doing.Waiting -> "asking the model"
                is AgentLoop.Doing.Planning -> "planning"
                is AgentLoop.Doing.Thinking -> "thinking"
                is AgentLoop.Doing.Retrying -> "retrying"
                is AgentLoop.Doing.Acting -> StepText.title(doing.act)
            }
            AgentRuntime.setDoing(text)
            notifyNow(text)
        }
        agent.onRecover = { stage ->
            val text = when (stage?.part) {
                Recovery.Part.BOARD ->
                    "waiting for the board" + (if (stage.ask) " - plug it in again" else "")
                Recovery.Part.CAPTURE ->
                    "waiting for the screen capture" + (if (stage.ask) " - allow it again" else "")
                null -> null
            }
            AgentRuntime.setNotice(text)
            notifyNow(text ?: AgentRuntime.doing.value)
        }
        agent.onStep = { publish(agent) }
        agent.onFinish = { publish(agent) }
        // A board that stops answering mid-command: drop the link so it reconnects, then retry once.
        graph?.board?.commands?.onTimeout = { command ->
            DebugLog.write("command '$command' timed out; dropping the link to recover")
            graph?.board?.dropLink()
        }
    }

    private fun publish(agent: AgentLoop) {
        AgentRuntime.publish(agent.task.value, agent.plan.value, agent.steps.value, agent.shots.value)
    }

    private fun notifyNow(doing: String) {
        runCatching { notifier?.notify(NOTIF_ID, notification(AgentRuntime.task.value, doing)) }
    }

    private fun failRun(reason: String) {
        DebugLog.write("run failed: $reason")
        AgentRuntime.setNotice(reason)
        AgentRuntime.setDoing("")
    }

    private fun endRun() {
        AgentRuntime.setRunning(false)
        AgentRuntime.setDoing("")
        notifyNow(AgentRuntime.notice.value ?: "finished")
        teardown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopRun(reason: String) {
        DebugLog.write("stopping: $reason")
        runCatching { loop?.stop() }
        runJob?.cancel()
        runJob = null
        AgentRuntime.setRunning(false)
        AgentRuntime.setDoing("")
        AgentRuntime.setNotice(reason)
        teardown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Let go of the button, stop the capture, close the trace. Safe to call twice. */
    private fun teardown() {
        val g = graph ?: return
        val hands = gestures
        lifecycleScope.launch {
            if (hands != null) runCatching { hands.reset() }
            runCatching { g.capture.stop() }
            g.trace.close()
        }
    }

    override fun onDestroy() {
        // The graph belongs to the process (AgentApp), not to this service: keep the board link for the next
        // run, only the capture is stopped (teardown).
        graph = null
        gestures = null
        loop = null
        super.onDestroy()
    }

    // MARK: - notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL, getString(R.string.notif_channel_run), NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_run_desc) }
        notifier?.createNotificationChannel(channel)
    }

    private fun notification(task: String, doing: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (task.isEmpty()) getString(R.string.notif_run_title) else task.take(60)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(doing)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.esp32agent.android.STOP"
        const val EXTRA_TASK = "task"
        const val EXTRA_MODE = "mode"
        const val MODE_RUN = "run"
        const val MODE_CALIBRATE = "calibrate"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 41

        /** True while the app's own UI is in front: the run holds, so the agent never acts on itself. */
        val uiInFront = MutableStateFlow(false)

        /** Ask for the screen capture, then start a run with the result. */
        fun start(context: Context, task: String, resultCode: Int, data: Intent) {
            val intent = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** Calibrate the relative pointer (a stock-firmware board); also needs a capture consent. */
        fun startCalibration(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_MODE, MODE_CALIBRATE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
