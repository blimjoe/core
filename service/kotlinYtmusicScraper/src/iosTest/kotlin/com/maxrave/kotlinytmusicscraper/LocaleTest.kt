package com.maxrave.kotlinytmusicscraper

import kotlin.test.Test
import kotlin.test.assertTrue

class LocaleTest {
    @Test fun requestLocaleUsesLanguageAndRegionInTheirOwnFields() {
        assertTrue(getCountry().matches(Regex("[A-Z]{2}|[0-9]{3}")), "YouTube gl must be a region")
        assertTrue(getLanguage().matches(Regex("[a-z]{2,3}")), "YouTube hl must be a language")
    }
}
