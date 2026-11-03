package com.example.esp32agent.android.board

import java.util.Locale

/** The board did not answer in time; the link is still up (a disconnect throws [DeviceError] instead). */
class BoardTimeout(val command: String, val seconds: Double) :
    RuntimeException("the board did not answer '$command' within " + String.format(Locale.US, "%.0f", seconds) + " s")

/** The BLE link to the board is gone, or the board answered `err`. */
class DeviceError(message: String) : RuntimeException(message)

/** A model endpoint answered with an error, or not at all. */
class LlmError(message: String) : RuntimeException(message)

/** The screen feed is gone; acting now would be guessing. */
class BlindError(reason: String = "lost the phone screen: allow the capture again") : RuntimeException(reason)

/** The action needs a pointer mode or a firmware build this board does not have. */
class UnsupportedError(message: String) : RuntimeException(message)