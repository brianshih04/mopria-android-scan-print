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
    fun fromEsclIntersectsResolutionsWithAppDefaults() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(resolutions = listOf(150, 300, 600, 1200))
        )
        // App offers 150/300/600; scanner also supports 1200 but that's not in the app set.
        assertEquals(setOf(150, 300, 600), caps.supportedResolutions)
    }

    @Test
    fun fromEsclFallsBackToAppDefaultsWhenNoOverlap() {
        val caps = ScannerCapabilities.fromEscl(
            EsclCapabilities(resolutions = listOf(200, 400, 800))
        )
        // Scanner supports only 200/400/800; none overlap app's 150/300/600.
        // Should fall back to app defaults so the user sees something.
        assertEquals(setOf(150, 300, 600), caps.supportedResolutions)
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
}
