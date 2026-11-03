package com.example.esp32agent.android.util

/** Seconds since the epoch, like the iPhone app's `now()` (Date.timeIntervalSince1970). */
fun now(): Double = System.currentTimeMillis() / 1000.0