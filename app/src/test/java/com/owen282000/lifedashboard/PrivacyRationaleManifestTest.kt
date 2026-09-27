package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Health Connect's privacy policy link must land on the privacy policy, not on the app's home
 * screen: Play reviews this. Up to Android 13 Health Connect sends
 * ACTION_SHOW_PERMISSIONS_RATIONALE, from Android 14 VIEW_PERMISSION_USAGE with the
 * HEALTH_PERMISSIONS category through an alias that only the system may start.
 */
class PrivacyRationaleManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val application: Element = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement
        .children("application")
        .single()

    private val privacyActivity = ".${PrivacyPolicyActivity::class.java.simpleName}"

    @Test
    fun theRationaleActionOpensThePrivacyPolicy() {
        assertEquals(listOf(privacyActivity), componentsHandling(RATIONALE_ACTION))
        val activity = application.children("activity").single { it.attr("name") == privacyActivity }
        assertEquals("Health Connect starts it from its own process", "true", activity.attr("exported"))
    }

    @Test
    fun thePermissionUsageActionOpensThePrivacyPolicyThroughTheGuardedAlias() {
        assertEquals(listOf("ViewPermissionUsageActivity"), componentsHandling(USAGE_ACTION))
        val alias = application.children("activity-alias").single { it.attr("name") == "ViewPermissionUsageActivity" }
        assertEquals(privacyActivity, alias.attr("targetActivity"))
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.attr("permission"))
        assertEquals("true", alias.attr("exported"))
        val filter = alias.children("intent-filter").single { f -> f.children("action").any { it.attr("name") == USAGE_ACTION } }
        assertTrue(filter.children("category").any { it.attr("name") == "android.intent.category.HEALTH_PERMISSIONS" })
    }

    /** Every component, activity or alias, with an intent filter for [action]. More than one would show a chooser. */
    private fun componentsHandling(action: String): List<String> =
        (application.children("activity") + application.children("activity-alias"))
            .filter { component ->
                component.children("intent-filter").any { filter ->
                    filter.children("action").any { it.attr("name") == action }
                }
            }
            .map { it.attr("name") }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private companion object {
        const val RATIONALE_ACTION = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        const val USAGE_ACTION = "android.intent.action.VIEW_PERMISSION_USAGE"
    }
}
