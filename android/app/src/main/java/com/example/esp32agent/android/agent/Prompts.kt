package com.example.esp32agent.android.agent

import android.content.Context

/**
 * The model prompts, plain text in the app's assets (`android/app/src/main/assets/prompts/`), the Android
 * counterpart of the repository's `prompts/` folder the iPhone app bundles. actor and planner are the shared
 * rules plus the app-only rules, exactly as Prompts.swift composes them.
 *
 * The action list in actor.md and the grammar in Actions.kt must stay in sync, as prompts/README.md asks.
 */
class Prompts(val planner: String, val actorServer: String, val appRules: String) {
    constructor(context: Context) : this(load(context, "planner"), load(context, "actor"), load(context, "app-rules"))

    val actor: String get() = actorServer + "\n" + appRules
    val plannerApp: String get() = planner + "\n" + appRules

    companion object {
        /** One file from assets/prompts, without its final newline (the newline is not part of the prompt). */
        fun load(context: Context, name: String): String {
            val text = context.assets.open("prompts/$name.md").bufferedReader().use { it.readText() }
            return if (text.endsWith("\n")) text.dropLast(1) else text
        }
    }
}
