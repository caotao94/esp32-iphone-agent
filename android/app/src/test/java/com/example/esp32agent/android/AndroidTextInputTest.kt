package com.example.esp32agent.android

import com.example.esp32agent.android.board.DeviceError
import com.example.esp32agent.android.board.HidDevice
import com.example.esp32agent.android.gestures.AndroidTextInput
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
    fun asciiIsPastedSoAPinyinKeyboardCannotComposeIt() = runTest {
        val device = FakeDevice()
        val clips = mutableListOf<String>()
        enterText(device, AndroidTextInput(device) { clips += it }, "v2ray", log = {})
        assertEquals(listOf("v2ray"), clips)
        assertEquals(listOf("key ctrl+v"), device.sent)
    }

    @Test
    fun chineseIsPasted() = runTest {
        val device = FakeDevice()
        val clips = mutableListOf<String>()
        enterText(device, AndroidTextInput(device) { clips += it }, "5090显卡", log = {})
        assertEquals(listOf("5090显卡"), clips)
        assertEquals(listOf("key ctrl+v"), device.sent)
    }

    @Test
    fun asciiFallsBackToTheKeyboardOnlyWhenTheClipboardRefuses() = runTest {
        val device = FakeDevice()
        val logged = mutableListOf<String>()
        enterText(device, AndroidTextInput(device) { error("no clipboard") }, "hello", log = { logged += it })
        assertEquals(listOf("type hello"), device.sent)
        assertEquals(1, logged.size)
    }

    @Test
    fun unicodeWithoutAClipboardFailsWithoutSendingAnything() = runTest {
        val device = FakeDevice()
        val error = runCatching {
            enterText(device, AndroidTextInput(device) { error("no clipboard") }, "你好", log = {})
        }.exceptionOrNull()
        assertTrue(error is DeviceError)
        assertTrue(device.sent.isEmpty())
    }

    @Test
    fun emptyTextSendsNothing() = runTest {
        val device = FakeDevice()
        enterText(device, AndroidTextInput(device) { error("must not be called") }, "", log = {})
        assertTrue(device.sent.isEmpty())
    }
}
