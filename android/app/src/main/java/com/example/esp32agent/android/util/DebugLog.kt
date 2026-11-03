package com.example.esp32agent.android.util

import android.util.Log

/** The app's own log: a ring buffer the diagnostics screen shows, plus logcat. Port of DebugLog.swift. */
object DebugLog {
    private const val TAG = "esp32agent"
    private const val LIMIT = 400
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun write(message: String) {
        for (line in message.split('\n')) {
            lines.addLast(clock() + " " + line)
            if (lines.size > LIMIT) lines.removeFirst()
        }
        Log.d(TAG, message)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    private fun clock(): String {
        val ms = System.currentTimeMillis() % 86_400_000  // time of day, UTC: no Calendar needed
        return String.format("%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}