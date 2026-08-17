package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintCapabilitiesTest {

    @Test
    fun emptyHasNoOptions() {
        assertFalse(PrintCapabilities.EMPTY.hasAnyOption())
    }

    @Test
    fun copiesRangeAloneCountsAsOption() {
        val caps = PrintCapabilities(copies = 1..5, media = emptyList(), sides = emptyList(), colorModes = emptyList(), qualities = emptyList(), orientations = emptyList())
        assertTrue(caps.hasAnyOption())
    }

    @Test
    fun copiesRange1to1DoesNotCountAsOption() {
        val caps = PrintCapabilities(copies = 1..1, media = listOf("iso_a4_210x297mm"), sides = emptyList(), colorModes = emptyList(), qualities = emptyList(), orientations = emptyList())
        // copies 1..1 is not configurable, but media is present.
        assertTrue(caps.hasAnyOption())
    }

    @Test
    fun coerceKeepsSupportedValues() {
        val caps = PrintCapabilities(
            copies = 1..5,
            media = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
            sides = listOf("one-sided", "two-sided-long-edge"),
            colorModes = listOf("color", "monochrome"),
            qualities = listOf("draft", "normal"),
            orientations = listOf("portrait", "landscape"),
        )
        val options = PrintOptions(
            copies = 3,
            media = "iso_a4_210x297mm",
            sides = "two-sided-long-edge",
            colorMode = "color",
            quality = "normal",
            orientation = "portrait",
        )
        val coerced = options.coerceTo(caps)
        assertEquals(3, coerced.copies)
        assertEquals("iso_a4_210x297mm", coerced.media)
        assertEquals("two-sided-long-edge", coerced.sides)
        assertEquals("color", coerced.colorMode)
        assertEquals("normal", coerced.quality)
        assertEquals("portrait", coerced.orientation)
    }

    @Test
    fun coerceDropsUnsupportedValues() {
        val caps = PrintCapabilities(
            copies = 1..1,
            media = listOf("iso_a4_210x297mm"),
            sides = listOf("one-sided"),
            colorModes = listOf("monochrome"),
            qualities = emptyList(),
            orientations = emptyList(),
        )
        val options = PrintOptions(
            copies = 5,
            media = "na_letter_8.5x11in",
            sides = "two-sided-long-edge",
            colorMode = "color",
            quality = "normal",
            orientation = "landscape",
        )
        val coerced = options.coerceTo(caps)
        assertNull(coerced.copies)       // 5 not in 1..1
        assertNull(coerced.media)        // letter not in [a4]
        assertNull(coerced.sides)        // two-sided not in [one-sided]
        assertNull(coerced.colorMode)    // color not in [monochrome]
        assertNull(coerced.quality)      // qualities empty
        assertNull(coerced.orientation)  // orientations empty
    }

    @Test
    fun coerceNullOptionsStayNull() {
        val caps = PrintCapabilities(
            copies = 1..10,
            media = listOf("iso_a4_210x297mm"),
            sides = listOf("one-sided"),
            colorModes = listOf("color"),
            qualities = listOf("normal"),
            orientations = listOf("portrait"),
        )
        val options = PrintOptions() // all null
        val coerced = options.coerceTo(caps)
        assertNull(coerced.copies)
        assertNull(coerced.media)
        assertNull(coerced.sides)
        assertNull(coerced.colorMode)
        assertNull(coerced.quality)
        assertNull(coerced.orientation)
    }

    @Test
    fun defaultPrintOptionsPreferA4WhenAdvertised() {
        val caps = PrintCapabilities(
            copies = 1..1,
            media = listOf(DEFAULT_IPP_MEDIA, "na_letter_8.5x11in"),
            sides = emptyList(),
            colorModes = emptyList(),
            qualities = emptyList(),
            orientations = emptyList(),
        )

        assertEquals(DEFAULT_IPP_MEDIA, caps.defaultPrintOptions(null).media)
        assertEquals(DEFAULT_IPP_MEDIA, caps.defaultPrintOptions(ScanDocumentSize.Auto).media)
        assertEquals("na_letter_8.5x11in", caps.defaultPrintOptions(ScanDocumentSize.Letter).media)
    }

    @Test
    fun coerceCopiesBoundary() {
        val caps = PrintCapabilities(copies = 2..4, media = emptyList(), sides = emptyList(), colorModes = emptyList(), qualities = emptyList(), orientations = emptyList())
        assertNull(PrintOptions(copies = 1).coerceTo(caps).copies)  // below range
        assertEquals(2, PrintOptions(copies = 2).coerceTo(caps).copies)  // at min
        assertEquals(4, PrintOptions(copies = 4).coerceTo(caps).copies)  // at max
        assertNull(PrintOptions(copies = 5).coerceTo(caps).copies)  // above range
    }
}
