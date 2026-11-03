package com.example.esp32agent.android

import com.example.esp32agent.android.util.normalizeApiKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyTest {
    @Test
    fun removesClipboardWhitespaceAndInvisibleMarks() {
        assertEquals("test-key", normalizeApiKey("\uFEFF\t test-\r\nkey\u200B\u00A0\u202F \n"))
        assertEquals("", normalizeApiKey(" \t\r\n\u200B\uFEFF"))
    }

    @Test
    fun leavesTokenPunctuationAndCaseUnchanged() {
        val token = "Test.A_b-9+/="
        assertEquals(token, normalizeApiKey(token))
        assertEquals(token, normalizeApiKey(normalizeApiKey(token)))
    }
}
