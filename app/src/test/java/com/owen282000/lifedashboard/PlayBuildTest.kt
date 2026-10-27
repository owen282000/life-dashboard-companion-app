package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Google Play build is paid, so it asks for no donation (P1-13): src/play switches the Ko-fi
 * row in About off, the GitHub and F-Droid builds keep it. Those two also point to the Play
 * listing (P1-27), which the Play build itself has no use for. Only the Play build asks for a
 * rating (P1-28): it has the Rate the app row, and its manifest names PlayApplication, which
 * brings Play's In-App Review; the other builds run the plain application and never ask.
 */
class PlayBuildTest {

    private fun bool(sourceSet: String, name: String): Boolean {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/$sourceSet/res/values/bools.xml"))
        val bools = doc.getElementsByTagName("bool")
        for (i in 0 until bools.length) {
            val node = bools.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == name) return node.textContent.trim().toBoolean()
        }
        error("$name is not in src/$sourceSet")
    }

    @Test
    fun theFreeBuildsShowTheDonationRow() {
        assertEquals(true, bool("main", "show_donation"))
    }

    @Test
    fun thePlayBuildDoesNot() {
        assertEquals(false, bool("play", "show_donation"))
    }

    @Test
    fun theFreeBuildsPointToThePlayListing() {
        assertEquals(true, bool("main", "show_play_listing"))
    }

    @Test
    fun thePlayBuildDoesNotPointToItself() {
        assertEquals(false, bool("play", "show_play_listing"))
    }

    @Test
    fun theFreeBuildsHaveNoRateRow() {
        assertEquals(false, bool("main", "show_rate_app"))
    }

    @Test
    fun thePlayBuildHasTheRateRow() {
        assertEquals(true, bool("play", "show_rate_app"))
    }

    private fun applicationName(sourceSet: String): String? {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/$sourceSet/AndroidManifest.xml"))
        val application = doc.getElementsByTagName("application").item(0) ?: return null
        return application.attributes.getNamedItemNS("http://schemas.android.com/apk/res/android", "name")?.nodeValue
    }

    @Test
    fun theFreeBuildsRunThePlainApplication() {
        assertEquals(".LifeDashboardApplication", applicationName("main"))
    }

    @Test
    fun thePlayBuildRunsTheApplicationThatAsksForARating() {
        assertEquals(".PlayApplication", applicationName("play"))
    }
}
