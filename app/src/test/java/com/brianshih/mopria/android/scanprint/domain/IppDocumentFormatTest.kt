
package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IppDocumentFormatTest {

    @Test
    fun producibleOrderPrefersPdfFirst() {
        assertEquals(IppDocumentFormat.PDF, IppDocumentFormat.producible.first())
    }

    @Test
    fun selectMatchesCaseInsensitive() {
        assertEquals(
            IppDocumentFormat.PDF,
            IppDocumentFormat.select(listOf("APPLICATION/PDF"), IppDocumentFormat.producible),
        )
    }

    @Test
    fun selectTrimsWhitespace() {
        assertEquals(
            IppDocumentFormat.JPEG,
            IppDocumentFormat.select(listOf("  image/jpeg  "), IppDocumentFormat.producible),
        )
    }

    @Test
    fun selectReturnsNullForNoMatch() {
        assertNull(IppDocumentFormat.select(listOf("text/html", "image/tiff"), IppDocumentFormat.producible))
    }

    @Test
    fun selectDoesNotTreatOctetStreamAsUniversalMatch() {
        // application/octet-stream should NOT match any producible format.
        assertNull(IppDocumentFormat.select(listOf("application/octet-stream"), IppDocumentFormat.producible))
    }

    @Test
    fun selectPrefersPwgRasterOverPclmWhenBothSupported() {
        assertEquals(
            IppDocumentFormat.PWG_RASTER,
            IppDocumentFormat.select(
                listOf("application/PCLm", "image/pwg-raster"),
                IppDocumentFormat.producible,
            ),
        )
    }

    @Test
    fun producibleContainsAllFiveFormats() {
        assertEquals(5, IppDocumentFormat.producible.size)
        assertTrue(IppDocumentFormat.PDF in IppDocumentFormat.producible)
        assertTrue(IppDocumentFormat.PWG_RASTER in IppDocumentFormat.producible)
        assertTrue(IppDocumentFormat.PCLM in IppDocumentFormat.producible)
        assertTrue(IppDocumentFormat.JPEG in IppDocumentFormat.producible)
        assertTrue(IppDocumentFormat.PNG in IppDocumentFormat.producible)
    }
}
