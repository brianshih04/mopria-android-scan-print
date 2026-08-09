package com.brianshih.mopria.android.scanprint.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageManagerTest {
    @Test
    fun fromTagRecognizesEverySelectableLanguage() {
        AppLanguage.userSelectable.drop(1).forEach { language ->
            assertEquals(language, AppLanguage.fromTag(language.tag))
        }
    }

    @Test
    fun fromTagFallsBackToSystemForUnknownValues() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("it"))
    }

    @Test
    fun languageForMapsChineseScriptAndRegionCorrectly() {
        assertEquals(AppLanguage.TraditionalChinese, LanguageManager.languageFor(Locale.forLanguageTag("zh-TW")))
        assertEquals(AppLanguage.TraditionalChinese, LanguageManager.languageFor(Locale.Builder().setLanguage("zh").setScript("Hant").build()))
        assertEquals(AppLanguage.SimplifiedChinese, LanguageManager.languageFor(Locale.CHINA))
        assertEquals(AppLanguage.SimplifiedChinese, LanguageManager.languageFor(Locale.Builder().setLanguage("zh").setScript("Hans").build()))
    }

    @Test
    fun languageForReturnsNullForUnsupportedSystemLanguage() {
        assertNull(LanguageManager.languageFor(Locale.ITALIAN))
    }
}
