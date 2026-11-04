package com.example.esp32agent.android

import com.example.esp32agent.android.apps.AppSearch
import com.example.esp32agent.android.apps.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val apps = listOf(
        InstalledApp("微信", "com.tencent.mm"),
        InstalledApp("Google", "com.google.android.googlequicksearchbox"),
        InstalledApp("Google Maps", "com.google.android.apps.maps"),
        InstalledApp("X", "com.twitter.android"),
    )

    @Test
    fun usesExactChineseAppNameFromGoal() {
        assertEquals(listOf("微信"), AppSearch.matching("打开微信给朋友发消息", apps).map { it.label })
    }

    @Test
    fun matchesEnglishIgnoringCaseAndSpacesAndPrefersSpecificLabel() {
        assertEquals(listOf("Google Maps", "Google"),
            AppSearch.matching("Open google maps to find a route", apps).map { it.label })
    }

    @Test
    fun doesNotMatchOneLetterAppInsideAnotherWord() {
        assertFalse(AppSearch.matching("Explain the next task", apps).any { it.label == "X" })
        assertEquals(listOf("X"), AppSearch.matching("Open X", apps).map { it.label })
    }

    @Test
    fun oneLetterAppNextToChineseTextIsNamed() {
        assertEquals(listOf("X"), AppSearch.matching("打开x看看新闻", apps).map { it.label })
    }

    @Test
    fun aLatinWordFindsTheAppItStarts() {
        val more = listOf(InstalledApp("v2rayNG", "com.v2ray.ang"), InstalledApp("OpenVPN", "net.openvpn.openvpn"))
        assertEquals(listOf("v2rayNG"), AppSearch.matching("打开v2ray连上代理", more).map { it.label })
        // A short common word does not name a longer app.
        assertEquals(emptyList<String>(), AppSearch.matching("open the settings", more).map { it.label })
    }

    @Test
    fun aShorterExactNameIsNotMistakenForALongerApp() {
        assertEquals(listOf("Google"), AppSearch.matching("search google for the weather", apps).map { it.label })
    }

    @Test
    fun noMatchDoesNotInventAppHints() {
        assertEquals("", AppSearch.hints("设置明天的闹钟", apps))
    }

    @Test
    fun promptsUseInstalledLabelAndLauncherSearch() {
        val hints = AppSearch.hints("打开微信", apps)
        assertTrue(hints.contains("- 微信"))
        assertTrue(hints.contains("app-list search"))
        assertTrue(hints.contains("already open"))
    }

}
