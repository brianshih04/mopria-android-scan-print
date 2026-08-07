package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.pdl.ColorSpace
import com.hp.jipp.pdl.RenderableDocument
import com.hp.jipp.pdl.RenderablePage
import com.hp.jipp.pdl.pwg.PwgWriter
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the JVM-testable parts of IPP rasterization: the PwgWriter + RenderablePage contract (the
 * approach [IppRasterizer] uses), and the print format preference. The Android PdfRenderer → bitmap
 * path in [IppRasterizer] itself needs a real device/printer to validate end-to-end.
 */
class IppRasterizerTest {

    @Test
    fun pwgWriterProducesValidRasterFromSyntheticPage() {
        val document = object : RenderableDocument() {
            override val dpi = 300
            override fun iterator() = listOf(solidPage(16, 16)).iterator()
        }
        val out = ByteArrayOutputStream()
        PwgWriter(out).use { it.write(document) }
        val bytes = out.toByteArray()
        // PWG-Raster begins with the "RaS2" magic, followed by per-page headers + pixel data.
        assertTrue("output too small: ${bytes.size}", bytes.size > 50)
        assertEquals("PWG-Raster magic should start with 'R'", 'R'.code.toByte(), bytes[0])
    }

    @Test
    fun formatPreferencePrefersPdfThenPwgRasterThenPclm() {
        val producible = IppDocumentFormat.producible // [PDF, PWG-Raster, PCLm]
        assertEquals(IppDocumentFormat.PDF, IppDocumentFormat.select(listOf("application/pdf", "image/pwg-raster"), producible))
        assertEquals(IppDocumentFormat.PWG_RASTER, IppDocumentFormat.select(listOf("image/pwg-raster", "application/PCLm"), producible))
        assertEquals(IppDocumentFormat.PCLM, IppDocumentFormat.select(listOf("application/PCLm"), producible))
        assertNull(IppDocumentFormat.select(listOf("image/urf"), producible))
    }

    private fun solidPage(width: Int, height: Int): RenderablePage = object : RenderablePage(width, height) {
        override fun render(yOffset: Int, swathHeight: Int, colorSpace: ColorSpace, byteArray: ByteArray) {
            byteArray.fill(0xFF.toByte()) // solid white in any color space
        }
    }
}
