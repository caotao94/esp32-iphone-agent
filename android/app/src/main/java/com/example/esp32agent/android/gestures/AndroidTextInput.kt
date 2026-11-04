package com.example.esp32agent.android.gestures

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice

/**
 * Enters text by pasting it: this app puts the text in the Android clipboard, and the board sends Ctrl+V.
 *
 * All text is pasted, not only Unicode. Keys typed on the USB keyboard go through the phone's input method,
 * and a Chinese (pinyin) keyboard turns letters into a composition ("v2ray" -> "v'2'ra'y" candidates), so the
 * same keystrokes give different text depending on the keyboard's current mode. A paste is committed as is,
 * whatever that mode is, and it is the only path for Chinese and emoji anyway (the HID keyboard is ASCII-only).
 *
 * The clipboard is intentionally left with the pasted text. Reading and restoring another app's clipboard is
 * restricted on recent Android versions, and silently destroying it would be worse than leaving the text the
 * user asked the agent to enter. The clip is marked sensitive, so Android does not preview it (a password).
 */
class AndroidTextInput(private val device: HidDevice, private val setClipboard: (String) -> Unit) {
    constructor(context: Context, device: HidDevice) : this(device, clipboardOf(context))

    /** The clipboard did not take the text; nothing was sent to the board, so another path is safe. */
    class ClipboardRefused(message: String) : RuntimeException(message)

    /** Paste `text` into the focused field. Throws [ClipboardRefused] before anything reaches the board. */
    suspend fun type(text: String) {
        if (text.isEmpty()) return
        try {
            setClipboard(text)
        } catch (e: Exception) {
            throw ClipboardRefused("could not put text in the Android clipboard: ${e.message ?: "clipboard unavailable"}")
        }
        // The command reply is still awaited, so normal timeout handling applies and the action is never
        // silently repeated: a failure from here on propagates, it is not retried another way.
        device.send("key ctrl+v")
    }

    private companion object {
        fun clipboardOf(context: Context): (String) -> Unit {
            val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
            return { text ->
                val clip = ClipData.newPlainText("ESP32 Agent", text)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
            }
        }
    }
}

/**
 * The `type` action for both pointer backends: paste when the clipboard path exists; printable ASCII falls
 * back to the HID keyboard only when the clipboard refuses the text (nothing was pasted, so nothing doubles).
 */
suspend fun enterText(device: HidDevice, input: AndroidTextInput?, text: String, log: (String) -> Unit) {
    if (input != null) {
        try {
            input.type(text)
            return
        } catch (e: AndroidTextInput.ClipboardRefused) {
            if (!text.isAsciiHidText()) throw DeviceError(e.message ?: "clipboard unavailable")
            log("${e.message}; typing the ASCII text on the USB keyboard instead")
        }
    }
    if (!text.isAsciiHidText()) throw DeviceError("Chinese/Unicode input needs Android clipboard paste")
    device.send("type $text")
}

/** The USB keyboard path is valid only for printable ASCII. */
fun String.isAsciiHidText(): Boolean = all { it.code in 0x20..0x7e }
