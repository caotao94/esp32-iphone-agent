package com.example.esp32agent.android.screen

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Creating a virtual display does not mean its asynchronous first JPEG has arrived yet. */
suspend fun ScreenSource.awaitLiveFrame(
    timeoutMillis: Long = 10_000,
    capturing: () -> Boolean = { true },
): Boolean = withTimeoutOrNull(timeoutMillis) {
    while (capturing()) {
        if (blocked) return@withTimeoutOrNull false
        if (live && !blocked && frame?.isNotEmpty() == true) return@withTimeoutOrNull true
        delay(20)
    }
    false
} ?: false
