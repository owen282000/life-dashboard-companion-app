package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The names of the data types and the texts of the failure notification are in Dutch and German
 * too: a missing one would show English in the middle of a translated screen or notification.
 */
class TranslatedNamesTest {

    /** Every string and plural item of one locale, by name; a plural's items joined. */
    private fun strings(folder: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
            .documentElement
        val nodes = root.childNodes
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "string" || it.tagName == "plurals" }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private val locales = listOf("values", "values-nl", "values-de")

    @Test
    fun everyDataTypeHasItsOwnNameInEveryLocale() {
        for (type in HealthDataType.entries) {
            val name = "data_type_${type.name.lowercase()}"
            assertEquals(
                "$type shows the string named $name",
                R.string::class.java.getField(name).getInt(null),
                type.displayNameRes
            )
        }
        for (folder in locales) {
            val strings = strings(folder)
            for (type in HealthDataType.entries) {
                val text = strings["data_type_${type.name.lowercase()}"]
                assertTrue("$type has no name in $folder", !text.isNullOrBlank())
            }
        }
    }

    @Test
    fun theEnglishNamesAreTheOnesTheAppAlwaysShowed() {
        val english = strings("values")
        assertEquals("Steps", english["data_type_steps"])
        assertEquals("Exercise Sessions", english["data_type_exercise"])
        assertEquals("VO2 Max", english["data_type_vo2_max"])
    }

    @Test
    fun theFailureNotificationIsTranslated() {
        val names = listOf(
            "sync_failures_channel_name",
            "sync_failures_channel_description",
            "sync_failing_title",
            "sync_failing_text",
            "sync_failing_last_error",
            "sync_failing_health_connect_silent",
            "main_title_health_connect",
            "main_title_screen_time"
        )
        for (folder in locales) {
            val strings = strings(folder)
            names.forEach { assertTrue("$it is missing in $folder", !strings[it].isNullOrBlank()) }
        }
        assertEquals("Schermtijd", strings("values-nl")["main_title_screen_time"])
        assertEquals("Bildschirmzeit", strings("values-de")["main_title_screen_time"])
    }
}
