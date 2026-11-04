package com.example.esp32agent.android.apps

import java.util.Locale

/** A launchable app's label, as shown by the phone's launcher. */
data class InstalledApp(val label: String, val packageName: String)

/** Pure matching rules shared by the catalog and its tests. */
object AppSearch {
    private fun normalized(text: String): String = text.lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    /** Latin words and numbers in a goal ("打开v2ray" -> "v2ray"); Chinese text is matched as a whole. */
    private val LATIN_WORD = Regex("[a-z0-9]+")

    /**
     * Apps the goal names. A label matches when the goal contains it ("打开微信" names 微信), or when a Latin
     * word of the goal starts it and covers most of it ("v2ray" names v2rayNG, but "open" does not name
     * OpenVPN and "google" does not name Google Maps). Exact names come first, longer before shorter:
     * "Google Maps" beats "Google" in a goal naming both.
     */
    fun matching(task: String, apps: List<InstalledApp>, limit: Int = 8): List<InstalledApp> {
        val goal = normalized(task)
        if (goal.isEmpty()) return emptyList()
        val words = LATIN_WORD.findAll(task.lowercase(Locale.ROOT)).map { it.value }.filter { it.length >= 3 }.toList()
        return apps.asSequence()
            .map { it to normalized(it.label) }
            .filter { (_, label) -> label.isNotEmpty() }
            .mapNotNull { (app, label) ->
                val exact = goal.contains(label)
                val prefix = words.any { label.startsWith(it) && it.length * 3 >= label.length * 2 }
                if (exact || prefix) Triple(app, label, exact) else null
            }
            // One-letter Latin labels would match letters inside almost every English goal: "X" counts only as
            // a word of its own. Its neighbours may be Chinese ("打开X"), just not Latin letters or digits.
            .filter { (app, label, _) ->
                label.length > 1 || label.any { it.code > 0x7f } ||
                    Regex("(?i)(?<![a-z0-9])" + Regex.escape(app.label) + "(?![a-z0-9])").containsMatchIn(task)
            }
            .sortedWith(compareByDescending<Triple<InstalledApp, String, Boolean>> { it.third }
                .thenByDescending { it.second.length }
                .thenBy { it.first.label }.thenBy { it.first.packageName })
            .map { it.first }
            .distinctBy { it.label }
            .take(limit)
            .toList()
    }

    fun hints(task: String, apps: List<InstalledApp>): String {
        val matches = matching(task, apps)
        if (matches.isEmpty()) return ""
        return "Installed launcher apps matching this goal (exact labels from this phone):\n" +
            matches.joinToString("\n") { "- " + it.label } +
            "\nWhen opening one of these apps, use its exact label in the launcher's app-list search. " +
            "Do not page through home screens to hunt for it. If its screen is already open, continue there."
    }
}
