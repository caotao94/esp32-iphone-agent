package com.example.esp32agent.android.ui

import android.view.InputDevice
import android.view.MotionEvent

private fun Int.hasInputSource(source: Int): Boolean = (this and source) == source

/**
 * A read-only view of the input devices that Android has registered. The board's `hidinfo` response is
 * useful firmware-side telemetry, but it cannot tell us whether Android classified the USB interface as a
 * touchscreen. This snapshot is deliberately independent of the board and can be used before a run starts.
 */
data class AndroidInputDeviceInfo(
    val id: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val sources: Int,
    val sourceNames: List<String>,
    val virtual: Boolean,
    val x: AndroidInputAxisRange?,
    val y: AndroidInputAxisRange?,
) {
    val isTouchscreen: Boolean get() = sources.hasInputSource(InputDevice.SOURCE_TOUCHSCREEN)
    val isMouse: Boolean get() = sources.hasInputSource(InputDevice.SOURCE_MOUSE)
    val isLikelyBoard: Boolean get() = name.contains("ESP32", ignoreCase = true) ||
        name.contains("Agent", ignoreCase = true)

    fun sourceSummary(): String = if (sourceNames.isEmpty()) {
        "unknown (0x${sources.toString(16).padStart(8, '0')})"
    } else {
        sourceNames.joinToString(", ")
    }

    companion object {
        fun from(device: InputDevice): AndroidInputDeviceInfo {
            fun axis(axis: Int): AndroidInputAxisRange? {
                // Prefer the touchscreen range. Some devices expose X/Y for more than one source.
                val range = device.motionRanges.firstOrNull {
                    it.axis == axis && it.source.hasInputSource(InputDevice.SOURCE_TOUCHSCREEN)
                } ?: device.motionRanges.firstOrNull { it.axis == axis }
                return range?.let {
                    AndroidInputAxisRange(
                        min = it.min,
                        max = it.max,
                        flat = it.flat,
                        fuzz = it.fuzz,
                        resolution = it.resolution,
                        source = it.source,
                    )
                }
            }

            return AndroidInputDeviceInfo(
                id = device.id,
                name = device.name,
                vendorId = device.vendorId,
                productId = device.productId,
                sources = device.sources,
                sourceNames = names(device.sources),
                virtual = device.isVirtual,
                x = axis(MotionEvent.AXIS_X),
                y = axis(MotionEvent.AXIS_Y),
            )
        }

        /** Return every currently registered input device, tolerating devices disappearing during the read. */
        fun snapshot(): List<AndroidInputDeviceInfo> = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { id -> runCatching { InputDevice.getDevice(id) }.getOrNull() }
            .map { from(it) }
            .sortedWith(compareByDescending<AndroidInputDeviceInfo> { it.isTouchscreen }.thenBy { it.name })
            .toList()

        private fun names(sources: Int): List<String> = buildList {
            fun add(source: Int, name: String) {
                if ((sources and source) == source) add(name)
            }
            add(InputDevice.SOURCE_TOUCHSCREEN, "touchscreen")
            add(InputDevice.SOURCE_MOUSE, "mouse")
            add(InputDevice.SOURCE_TOUCHPAD, "touchpad")
            add(InputDevice.SOURCE_STYLUS, "stylus")
            add(InputDevice.SOURCE_TRACKBALL, "trackball")
            add(InputDevice.SOURCE_DPAD, "dpad")
            add(InputDevice.SOURCE_GAMEPAD, "gamepad")
            add(InputDevice.SOURCE_JOYSTICK, "joystick")
            add(InputDevice.SOURCE_KEYBOARD, "keyboard")
            add(InputDevice.SOURCE_ROTARY_ENCODER, "rotary")
        }
    }
}

object AndroidInputDiagnostics {
    fun snapshot(): List<AndroidInputDeviceInfo> = AndroidInputDeviceInfo.snapshot()
}

data class AndroidInputAxisRange(
    val min: Float,
    val max: Float,
    val flat: Float,
    val fuzz: Float,
    val resolution: Float,
    val source: Int,
)
