package com.example.esp32agent.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.esp32agent.android.ui.AppUi
import com.example.esp32agent.android.ui.Theme
import com.example.esp32agent.android.util.DebugLog

/**
 * Setup and the run button. A run itself lives in [AgentService]: this activity asks for the screen-capture
 * consent (Android 14+ only hands a projection to a running `mediaProjection` service), starts the service
 * with it, and then moves itself to the back so the agent works in the app the task needs, not in this one.
 *
 * That is what the iPhone app does with a private `suspend` call; here it is one public API.
 */
class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as AgentApp).graph }
    private var pendingTask: String? = null
    private var pendingCalibration = false
    private var pendingBoardScan = false

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = pendingTask
        val calibrating = pendingCalibration
        pendingTask = null
        pendingCalibration = false
        val data = result.data
        if (data == null || result.resultCode != RESULT_OK) {
            AgentRuntime.setNotice("screen capture was not allowed: a run cannot see without it")
            DebugLog.write("capture consent refused")
            return@registerForActivityResult
        }
        if (calibrating) AgentService.startCalibration(this, result.resultCode, data)
        else if (task != null) AgentService.start(this, task, result.resultCode, data)
        // Let the target app come to the front: the agent acts on what is on screen, not on this UI.
        moveTaskToBack(true)
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Notifications are optional; Bluetooth (or pre-Android-12 location) is required.
        if (!hasPermissions()) {
            AgentRuntime.setNotice("without Bluetooth permission the board cannot be reached")
            pendingTask = null
            pendingCalibration = false
            pendingBoardScan = false
            return@registerForActivityResult
        }
        if (pendingBoardScan) {
            pendingBoardScan = false
            graph.board.scan()
        } else if (pendingTask != null || pendingCalibration) {
            askForCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Theme { AppUi(graph, ::startRun, ::startCalibration, ::stopRun, ::scanBoard) } }
    }

    override fun onStart() {
        super.onStart()
        AgentService.uiInFront.value = true  // hold a run while this UI is in front
    }

    override fun onStop() {
        AgentService.uiInFront.value = false
        super.onStop()
    }

    private fun startRun(task: String) {
        if (task.isBlank()) return
        pendingTask = task
        pendingCalibration = false
        if (!hasPermissions()) {
            permissions.launch(needed())
            return
        }
        askForCapture()
    }

    private fun startCalibration() {
        pendingTask = null
        pendingCalibration = true
        if (!hasPermissions()) {
            permissions.launch(needed())
            return
        }
        askForCapture()
    }

    private fun stopRun() {
        AgentService.stop(this)
    }

    private fun scanBoard() {
        if (!hasPermissions()) {
            pendingBoardScan = true
            permissions.launch(needed())
        } else {
            graph.board.scan()
        }
    }

    private fun askForCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Touch coordinates refer to the whole display. An app-only capture can be
        // letterboxed and keeps showing the old app after navigation, so it cannot
        // safely drive absolute touches. Request the display explicitly on 14+.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                DebugLog.write("Requesting full display capture with MediaProjectionConfig (SDK ${Build.VERSION.SDK_INT})")
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } catch (e: Exception) {
                DebugLog.write("MediaProjectionConfig failed: ${e.message}, falling back to legacy API")
                manager.createScreenCaptureIntent()
            }
        } else {
            DebugLog.write("Using legacy createScreenCaptureIntent (SDK ${Build.VERSION.SDK_INT})")
            manager.createScreenCaptureIntent()
        }
        capture.launch(intent)
    }

    private fun needed(): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun hasPermissions(): Boolean = needed().filterNot { it == Manifest.permission.POST_NOTIFICATIONS }.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
