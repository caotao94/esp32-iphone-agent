package com.example.esp32agent.android.gestures

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice

/** The Android clipboard, behind an interface so the text path can be tested on the JVM. */
interface Clipboard {
    fun set(text: String)
    fun clear()
}

/** The system clipboard. The clip is marked sensitive, so Android does not preview it (it may be a password). */
class SystemClipboard(context: Context) : Clipboard {
    private val manager: ClipboardManager? = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun set(text: String) {
        val clip = ClipData.newPlainText("ESP32 Agent", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        (manager ?: throw IllegalStateException("no clipboard service")).setPrimaryClip(clip)
    }

    override fun clear() {
        manager?.clearPrimaryClip()
    }
}

/**
 * Enters text by pasting it: this app puts the text in the Android clipboard, and the board sends Ctrl+V.
 *
 * Letters are pasted, not typed. Keys typed on the USB keyboard go through the phone's input method, and a
 * Chinese (pinyin) keyboard turns letters into a composition ("v2ray" -> "v'2'ra'y" candidates), so the same
 * keystrokes give different text depending on the keyboard's current mode. A paste is committed as is, whatever
 * that mode is, and it is the only path for Chinese and emoji anyway (the HID keyboard is ASCII-only).
 *
 * The agent's clip is cleared once the paste has landed ([forget], after the screen settles) and when a run
 * ends, so a pasted password does not stay readable by the next app in front. The user's earlier clip was
 * already replaced by the paste; reading and restoring another app's clipboard is restricted on recent Android.
 */
class AndroidTextInput(private val device: HidDevice, private val clipboard: Clipboard) {
    constructor(context: Context, device: HidDevice) : this(device, SystemClipboard(context))

    /** The clipboard did not take the text; nothing was sent to the board, so another path is safe. */
    class ClipboardRefused(message: String) : RuntimeException(message)

    /** True while the clipboard holds text this agent put there. */
    private var placed = false

    /** Paste `text` into the focused field. Throws [ClipboardRefused] before anything reaches the board. */
    suspend fun type(text: String) {
        if (text.isEmpty()) return
        try {
            clipboard.set(text)
        } catch (e: Exception) {
            throw ClipboardRefused("could not put text in the Android clipboard: ${e.message ?: "clipboard unavailable"}")
        }
        placed = true
        // The command reply is still awaited, so normal timeout handling applies and the action is never
        // silently repeated: a failure from here on propagates, it is not retried another way.
        device.send("key ctrl+v")
    }

    /** Clear the clipboard if it still holds the agent's text. Never throws: a run must be able to end. */
    fun forget() {
        if (!placed) return
        placed = false
        runCatching { clipboard.clear() }
    }
}

/**
 * The `type` action for both pointer backends.
 * - Plain digits are typed key by key on the HID keyboard: a pinyin keyboard commits a digit as is when no
 *   composition is open (letters are never typed, so none is), and one-box-per-digit code fields and number
 *   pickers need one key per digit to move from box to box.
 * - Everything else is pasted when the clipboard path exists. Printable ASCII falls back to the HID keyboard
 *   only when the clipboard refuses the text (nothing was pasted, so nothing is entered twice).
 */
suspend fun enterText(device: HidDevice, input: AndroidTextInput?, text: String, log: (String) -> Unit) {
    if (text.isEmpty()) return
    if (text.all { it in '0'..'9' }) {
        device.send("type $text")
        return
    }
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
