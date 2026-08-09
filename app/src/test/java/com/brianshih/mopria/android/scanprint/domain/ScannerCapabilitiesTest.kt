package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerCapabilitiesTest {

    @Test
    fun defaultHasAllAppOptions() {
        val caps = ScannerCapabilities.DEFAULT
        assertEquals(setOf(150, 300, 600), caps.supportedResolutions)
        assertEquals(ScanInputSource.entries.toSet(), caps.supportedSources)
        assertEquals(ScanColorMode.entries.toSet(), caps.supportedColorModes)
    }

    @Test
    fun fromEsclKeepsSaneAdvertisedResolutions() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(resolutions = listOf(150, 300, 600, 1200))
        )
        assertEquals(setOf(150, 300, 600, 1200), caps.supportedResolutions)
    }

    @Test
    fun fromEsclKeepsScannerSpecificResolutions() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(resolutions = listOf(200, 400, 800))
        )
        assertEquals(setOf(200, 400, 800), caps.supportedResolutions)
    }

    @Test
    fun fromEsclMapsInputSources() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(inputSources = listOf("Platen", "Feeder"))
        )
        assertEquals(setOf(ScanInputSource.Flatbed, ScanInputSource.Adf), caps.supportedSources)
    }

    @Test
    fun fromEsclFlatbedOnly() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(inputSources = listOf("Platen"))
        )
        assertEquals(setOf(ScanInputSource.Flatbed), caps.supportedSources)
    }

    @Test
    fun fromEsclMapsColorModesCaseInsensitive() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(colorModes = listOf("rgb24", "grayscale8"))
        )
        assertEquals(setOf(ScanColorMode.Color, ScanColorMode.Grayscale), caps.supportedColorModes)
    }

    @Test
    fun fromEsclEmptySourcesFallsBackToAll() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(inputSources = emptyList())
        )
        assertEquals(ScanInputSource.entries.toSet(), caps.supportedSources)
    }

    @Test
    fun fromEsclEmptyColorModesFallsBackToAll() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(colorModes = emptyList())
        )
        assertEquals(ScanColorMode.entries.toSet(), caps.supportedColorModes)
    }

    @Test
    fun reconcileCoercesEverySettingToScannerCapabilities() {
        val caps = ScannerCapabilities(
            supportedResolutions = setOf(200, 400),
            supportedSources = setOf(ScanInputSource.Flatbed),
            supportedColorModes = setOf(ScanColorMode.Grayscale),
            maxAdfPages = 8,
        )

        val reconciled = caps.reconcile(
            ScanSettings(
                inputSource = ScanInputSource.Adf,
                resolutionDpi = 600,
                colorMode = ScanColorMode.Color,
                maxPages = 20,
                enhanceBackground = EnhancementStrength.Strong,
            ),
        )

        assertEquals(ScanInputSource.Flatbed, reconciled.inputSource)
        assertEquals(400, reconciled.resolutionDpi)
        assertEquals(ScanColorMode.Grayscale, reconciled.colorMode)
        assertEquals(8, reconciled.maxPages)
        assertEquals(null, reconciled.enhanceBackground)
    }

    @Test
    fun reconcileResolutionTieChoosesHigherValue() {
        val reconciled = ScannerCapabilities(
            supportedResolutions = setOf(200, 400),
        ).reconcile(ScanSettings(resolutionDpi = 300))

        assertEquals(400, reconciled.resolutionDpi)
    }

    @Test
    fun defaultSettingsMatchDocumentPreset() {
        val defaults = ScanSettings()
        assertEquals(ScanPreset.Document.defaultSettings(defaults), defaults)
        assertEquals(true, defaults.combineAsPdf)
        assertEquals(EnhancementStrength.Normal, defaults.enhanceBackground)
    }

    @Test
    fun photoPresetDisablesEnhancement() {
        val photo = ScanPreset.Photo.defaultSettings(ScanSettings())
        assertEquals(600, photo.resolutionDpi)
        assertEquals(false, photo.combineAsPdf)
        assertEquals(null, photo.enhanceBackground)
    }

    @Test
    fun ocrReconcilesToSafeResolutionAndGrayscale() {
        val reconciled = ScannerCapabilities(
            supportedResolutions = setOf(200, 300, 600),
            supportedColorModes = setOf(ScanColorMode.Color, ScanColorMode.Grayscale),
        ).reconcile(
            ScanSettings(
                resolutionDpi = 600,
                colorMode = ScanColorMode.Color,
                ocrMode = OcrMode.MlKit,
            ),
        )

        assertEquals(300, reconciled.resolutionDpi)
        assertEquals(ScanColorMode.Grayscale, reconciled.colorMode)
        assertEquals(OcrMode.MlKit, reconciled.ocrMode)
    }

    @Test
    fun ocrDisablesWhenScannerHasNoSafeResolution() {
        val reconciled = ScannerCapabilities(supportedResolutions = setOf(600)).reconcile(
            ScanSettings(resolutionDpi = 600, ocrMode = OcrMode.MlKit),
        )

        assertEquals(600, reconciled.resolutionDpi)
        assertEquals(OcrMode.Disabled, reconciled.ocrMode)
    }
}
