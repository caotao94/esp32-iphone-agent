package com.example.esp32agent.android.store

import android.content.Context
import com.example.esp32agent.android.util.normalizeApiKey

/**
 * The model endpoint and the run knobs. Port of Services/Settings.swift.
 *
 * The iPhone app keeps the API key in the Keychain; this skeleton keeps it in the app's private
 * SharedPreferences, which is private to the app but not hardware-backed. Before publishing anything, move
 * [apiKey] to a Keystore-encrypted store (see docs/android.md).
 */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString(KEY_BASE_URL, "") ?: ""
        set(v) = p.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var apiKey: String
        get() = normalizeApiKey(p.getString(KEY_API_KEY, "") ?: "")
        set(v) = p.edit().putString(KEY_API_KEY, normalizeApiKey(v)).apply()

    /** The actor model: it picks every action, so it has to be a vision model. */
    var actorModel: String
        get() = p.getString(KEY_ACTOR, "") ?: ""
        set(v) = p.edit().putString(KEY_ACTOR, v.trim()).apply()

    /** The planner model; empty means "use the actor for both". */
    var plannerModel: String
        get() = p.getString(KEY_PLANNER, "") ?: ""
        set(v) = p.edit().putString(KEY_PLANNER, v.trim()).apply()

    var tokenParam: String
        get() = p.getString(KEY_TOKEN_PARAM, "auto") ?: "auto"
        set(v) = p.edit().putString(KEY_TOKEN_PARAM, v).apply()

    /** auto (probe the board) | abs (Android firmware) | rel (stock firmware + calibration). */
    var pointerMode: String
        get() = p.getString(KEY_POINTER, "auto") ?: "auto"
        set(v) = p.edit().putString(KEY_POINTER, v).apply()

    var maxSteps: Int
        get() = p.getInt(KEY_MAX_STEPS, 40)
        set(v) = p.edit().putInt(KEY_MAX_STEPS, v.coerceIn(1, 200)).apply()

    /** Which board was paired last, so a run can start without picking it again. */
    var pairedBoard: String
        get() = p.getString(KEY_BOARD, "") ?: ""
        set(v) = p.edit().putString(KEY_BOARD, v).apply()

    val hasModel: Boolean get() = baseUrl.isNotEmpty() && actorModel.isNotEmpty()

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_ACTOR = "actorModel"
        private const val KEY_PLANNER = "plannerModel"
        private const val KEY_TOKEN_PARAM = "tokenParam"
        private const val KEY_POINTER = "pointerMode"
        private const val KEY_MAX_STEPS = "maxSteps"
        private const val KEY_BOARD = "pairedBoard"
    }
}
