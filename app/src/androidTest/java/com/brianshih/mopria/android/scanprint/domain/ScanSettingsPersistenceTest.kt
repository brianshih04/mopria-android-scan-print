package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanSettingsPersistenceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(SettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun cleanupPreferences() = clearPreferences()

    @Test
    fun freshInstallDefaultsMatchDocumentPreset() {
        val store = SettingsStore(context)
        assertEquals(ScanPreset.Document, store.loadScanPreset())
        assertEquals(ScanPreset.Document.defaultSettings(ScanSettings()), store.loadScanSettings())
        assertFalse(store.loadScanSettings().searchablePdf)
    }

    @Test
    fun explicitlyDisabledEnhancementRemainsDisabled() {
        val store = SettingsStore(context)
        val disabled = ScanSettings(enhanceBackground = null)
        store.saveScanSettings(disabled)

        assertEquals(null, store.loadScanSettings().enhanceBackground)
    }

    @Test
    fun scannerSpecificResolutionPersists() {
        val store = SettingsStore(context)
        store.saveScanSettings(ScanSettings(resolutionDpi = 400, enhanceBackground = null))

        assertEquals(400, store.loadScanSettings().resolutionDpi)
    }

    @Test
    fun originalSizePersists() {
        val store = SettingsStore(context)
        store.saveScanSettings(ScanSettings(documentSize = ScanDocumentSize.Photo5x7))

        assertEquals(ScanDocumentSize.Photo5x7, store.loadScanSettings().documentSize)
    }

    @Test
    fun adfDuplexModePersists() {
        val store = SettingsStore(context)
        store.saveScanSettings(
            ScanSettings(inputSource = ScanInputSource.Adf, adfMode = ScanAdfMode.Duplex),
        )

        val loaded = store.loadScanSettings()
        assertEquals(ScanInputSource.Adf, loaded.inputSource)
        assertEquals(ScanAdfMode.Duplex, loaded.adfMode)
    }

    @Test
    fun manualDeviceAddressPersists() {
        val store = SettingsStore(context)
        store.saveManualDeviceAddress(" 10.1.121.175 ")

        assertEquals("10.1.121.175", store.loadManualDeviceAddress())
    }

    @Test
    fun imagePipelineAndOcrOptionsPersist() {
        val store = SettingsStore(context)
        val selectedLanguages = setOf(OcrLanguagePack.English, OcrLanguagePack.Japanese)
        store.saveOcrLanguagePacks(selectedLanguages)
        store.saveScanSettings(
            ScanSettings(
                ocrMode = OcrMode.MlKit,
                ocrLanguage = OcrLanguagePack.Japanese,
                searchablePdf = true,
                deskew = true,
                autoCrop = true,
                dropBlankPages = true,
            ),
        )

        val loaded = store.loadScanSettings()
        assertEquals(OcrMode.MlKit, loaded.ocrMode)
        assertEquals(true, loaded.searchablePdf)
        assertEquals(OcrLanguagePack.Japanese, loaded.ocrLanguage)
        assertEquals(selectedLanguages, store.loadOcrLanguagePacks())
        assertEquals(true, loaded.deskew)
        assertEquals(true, loaded.autoCrop)
        assertEquals(true, loaded.dropBlankPages)
    }

    @Test
    fun searchablePdfIsDisabledWhenOcrIsDisabled() {
        val store = SettingsStore(context)
        store.saveScanSettings(ScanSettings(searchablePdf = true, ocrMode = OcrMode.Disabled))

        assertFalse(store.loadScanSettings().searchablePdf)
    }

    @Test
    fun unsupportedOcrLanguagesCannotBecomePersistedSelections() {
        val store = SettingsStore(context)
        store.saveOcrLanguagePacks(setOf(OcrLanguagePack.Russian))

        assertEquals(OcrLanguagePack.defaultSelection, store.loadOcrLanguagePacks())
        assertEquals(OcrLanguagePack.SimplifiedChinese, store.loadActiveOcrLanguage())
    }
}
