package com.example.esp32agent.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Holds the one [AppGraph] for the process: the activity pairs and calibrates the board, the service runs
 * the agent, and both must share a single BLE link (two GATT clients on one board fight over it).
 */
class AgentApp : Application() {
    /** Survives activities and the service; cancelled with the process. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val graph: AppGraph by lazy { AppGraph(this, scope) }

    override fun onCreate() {
        super.onCreate()
        // A board that is already plugged in comes back without a tap - but only once the runtime permission
        // is there (Android 12+), otherwise the first connect would throw.
        if (canConnect()) graph.board.reconnect()
    }

    private fun canConnect(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(this, needed) == PackageManager.PERMISSION_GRANTED
    }
}