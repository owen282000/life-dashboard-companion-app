package com.owen282000.lifedashboard

import java.util.Locale

/**
 * What goes between the items of a list the app joins itself and shows or reads out: names of
 * data types, hosts, sync times. Arabic writes its own comma (U+060C), and a Latin one in the
 * middle of an Arabic line looks like a typo; every other language the app speaks keeps ", ".
 * Only for text on screen: what is stored or sent keeps its own separator.
 */
fun listSeparator(locale: Locale = Locale.getDefault()): String =
    if (locale.language == "ar") "، " else ", "
