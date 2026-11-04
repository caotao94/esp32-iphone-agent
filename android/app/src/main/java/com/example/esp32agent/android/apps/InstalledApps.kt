package com.example.esp32agent.android.apps

import android.content.Context
import android.content.Intent
import com.example.esp32agent.android.util.DebugLog

/** Reads only launchable apps; no QUERY_ALL_PACKAGES permission or background activity launch. */
class InstalledApps(context: Context) {
    private val context = context.applicationContext

    fun catalog(): List<InstalledApp> = try {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(launcher, 0)
        activities.asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveInfo ->
                // An activity declared with android:label="" loads as "" instead of falling back to the
                // app's name (v2rayNG does this); the launcher then shows the application label.
                val label = resolveInfo.loadLabel(pm).toString().trim().ifEmpty {
                    runCatching { pm.getApplicationLabel(resolveInfo.activityInfo.applicationInfo).toString().trim() }
                        .getOrDefault("")
                }
                InstalledApp(label, resolveInfo.activityInfo.packageName)
            }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.label to it.packageName }
            .sortedWith(compareBy<InstalledApp> { it.label }.thenBy { it.packageName })
            .toList()
    } catch (e: Exception) {
        DebugLog.write("app list unavailable: ${e.message}")
        emptyList()
    }

    /** The prompt hint for `task`; the log names only the matched labels, not every installed app. */
    fun appHints(task: String): String {
        val apps = catalog()
        DebugLog.write("app hints: ${apps.size} launcher apps, matched ${AppSearch.matching(task, apps).map { it.label }}")
        return AppSearch.hints(task, apps)
    }
}
