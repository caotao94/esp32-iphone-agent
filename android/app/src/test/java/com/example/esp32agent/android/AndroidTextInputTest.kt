package com.example.esp32agent.android

import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.gestures.AndroidTextInput
import com.example.esp32agent.android.gestures.Clipboard
import com.example.esp32agent.android.gestures.enterText
import com.example.esp32agent.android.gestures.isAsciiHidText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTextInputTest {
    private class FakeDevice : HidDevice {
        val sent = mutableListOf<String>()
        override val links = 1
        override val connected = true
        override suspend fun send(command: String): String {
            sent += command
            return ""
        }
        override suspend fun waitConnected(timeout: Double) = true
    }

    private class FakeClipboard(private val refuse: Boolean = false) : Clipboard {
        val clips = mutableListOf<String>()
        var clears = 0
            private set

        override fun set(text: String) {
            if (refuse) error("no clipboard")
            clips += text
        }

        override fun clear() {
            clears++
        }
    }

    @Test
    fun printableAsciiCanUseTheHidKeyboard() {
        assertTrue("hello 123 !".isAsciiHidText())
        assertTrue("".isAsciiHidText())
    }

    @Test
    fun unicodeCannotUseTheHidKeyboard() {
        assertFalse("你好".isAsciiHidText())
        assertFalse("hello 😊".isAsciiHidText())
    }

    @Test
    fun lettersArePastedSoAPinyinKeyboardCannotComposeThem() = runTest {
        val device = FakeDevice()
        val clipboard = FakeClipboard()
        enterText(device, AndroidTextInput(device, clipboard), "v2ray", log = {})
        assertEquals(listOf("v2ray"), clipboard.clips)
        assertEquals(listOf("key ctrl+v"), device.sent)
    }

    @Test
    fun chineseIsPasted() = runTest {
        val device = FakeDevice()
        val clipboard = FakeClipboard()
        enterText(device, AndroidTextInput(device, clipboard), "5090显卡", log = {})
        assertEquals(listOf("5090显卡"), clipboard.clips)
        assertEquals(listOf("key ctrl+v"), device.sent)
    }

    @Test
    fun plainDigitsAreTypedKeyByKeyForCodeBoxesAndPickers() = runTest {
        val device = FakeDevice()
        val clipboard = FakeClipboard()
        enterText(device, AndroidTextInput(device, clipboard), "654321", log = {})
        assertEquals(listOf("type 654321"), device.sent)
        assertTrue(clipboard.clips.isEmpty())
    }

    @Test
    fun asciiFallsBackToTheKeyboardOnlyWhenTheClipboardRefuses() = runTest {
        val device = FakeDevice()
        val logged = mutableListOf<String>()
        enterText(device, AndroidTextInput(device, FakeClipboard(refuse = true)), "hello", log = { logged += it })
        assertEquals(listOf("type hello"), device.sent)
        assertEquals(1, logged.size)
    }

    @Test
    fun unicodeWithoutAClipboardFailsWithoutSendingAnything() = runTest {
        val device = FakeDevice()
        val error = runCatching {
            enterText(device, AndroidTextInput(device, FakeClipboard(refuse = true)), "你好", log = {})
        }.exceptionOrNull()
        assertTrue(error is DeviceError)
        assertTrue(device.sent.isEmpty())
    }

    @Test
    fun emptyTextSendsNothing() = runTest {
        val device = FakeDevice()
        val clipboard = FakeClipboard()
        enterText(device, AndroidTextInput(device, clipboard), "", log = {})
        assertTrue(device.sent.isEmpty())
        assertTrue(clipboard.clips.isEmpty())
    }

    @Test
    fun forgetClearsOnlyTheAgentsOwnClipAndOnlyOnce() = runTest {
        val device = FakeDevice()
        val clipboard = FakeClipboard()
        val input = AndroidTextInput(device, clipboard)
        input.forget()  // nothing pasted yet: the user's clip is not touched
        assertEquals(0, clipboard.clears)
        input.type("secret-pass")
        input.forget()
        input.forget()  // already cleared
        assertEquals(1, clipboard.clears)
    }
}
