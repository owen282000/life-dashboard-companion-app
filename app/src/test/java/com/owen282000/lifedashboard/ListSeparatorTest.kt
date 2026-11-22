package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A list the app joins itself gets the Arabic comma in Arabic, and stays as it was everywhere else. */
class ListSeparatorTest {

    @Test
    fun arabicGetsItsOwnCommaInEveryRegion() {
        for (tag in listOf("ar", "ar-SA", "ar-EG", "ar-MA")) {
            assertEquals(tag, "، ", listSeparator(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun everyOtherLanguageTheAppSpeaksKeepsTheLatinComma() {
        // Also Japanese and Chinese, and Persian and Urdu, which the app shows in English.
        for (tag in listOf("en", "nl", "de", "fr", "es", "it", "pt-BR", "ja", "hi", "zh-CN", "fa", "ur")) {
            assertEquals(tag, ", ", listSeparator(Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun theHostsOfADeliveryFollowThePhonesLanguage() {
        val urls = listOf("https://ha.example.com/api/webhook/abc", "https://grafana.example.com/in?token=1")
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("ha.example.com، grafana.example.com", PartialDelivery.hosts(urls))
            Locale.setDefault(Locale.forLanguageTag("nl"))
            assertEquals("ha.example.com, grafana.example.com", PartialDelivery.hosts(urls))
        } finally {
            Locale.setDefault(before)
        }
    }
}
