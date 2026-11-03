package com.example.esp32agent.android.util

/** Bearer keys cannot contain whitespace; copied keys sometimes include line breaks or invisible marks. */
fun normalizeApiKey(value: String): String = value.filterNot {
    it.isWhitespace() || it == '\u200B' || it == '\uFEFF'
}
