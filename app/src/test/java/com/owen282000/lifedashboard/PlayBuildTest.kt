package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Google Play build is paid, so it asks for no donation (P1-13): src/play switches the Ko-fi
 * row in About off, the GitHub and F-Droid builds keep it.
 */
class PlayBuildTest {

    private fun showDonation(sourceSet: String): Boolean {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/$sourceSet/res/values/bools.xml"))
        val bools = doc.getElementsByTagName("bool")
        for (i in 0 until bools.length) {
            val node = bools.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == "show_donation") return node.textContent.trim().toBoolean()
        }
        error("show_donation is not in src/$sourceSet")
    }

    @Test
    fun theFreeBuildsShowTheDonationRow() {
        assertEquals(true, showDonation("main"))
    }

    @Test
    fun thePlayBuildDoesNot() {
        assertEquals(false, showDonation("play"))
    }
}
