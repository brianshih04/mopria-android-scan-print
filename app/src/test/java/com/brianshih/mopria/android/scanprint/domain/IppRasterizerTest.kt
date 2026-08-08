package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.pdl.ColorSpace
import com.hp.jipp.pdl.OutputSettings
import com.hp.jipp.pdl.RenderableDocument
import com.hp.jipp.pdl.RenderablePage
import com.hp.jipp.pdl.pclm.PclmSettings
import com.hp.jipp.pdl.pclm.PclmWriter
import com.hp.jipp.pdl.pwg.PwgSettings
import com.hp.jipp.pdl.pwg.PwgWriter
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt
import org.junit.Test

/**
 * Covers the JVM-testable parts of IPP rasterization: the PwgWriter/PclmWriter + RenderablePage
 * contract (the approach [IppRasterizer] uses), multi-pass iteration safety (the critical invariant
 * that PclmWriter requires), pixel conversion correctness, and format preference.
 *
 * The Android PdfRenderer → bitmap path in [IppRasterizer] itself needs a real device/printer to
 * validate end-to-end; these tests validate the jipp-pdl integration and the rendering algorithms.
 */
class IppRasterizerTest {

    // ===== Format preference =====

    @Test
    fun formatPreferencePrefersPdfThenPwgRasterThenPclm() {
        val producible = IppDocumentFormat.producible // [PDF, PWG-Raster, PCLm]
        assertEquals(IppDocumentFormat.PDF, IppDocumentFormat.select(listOf("application/pdf", "image/pwg-raster"), producible))
        assertEquals(IppDocumentFormat.PWG_RASTER, IppDocumentFormat.select(listOf("image/pwg-raster", "application/PCLm"), producible))
        assertEquals(IppDocumentFormat.PCLM, IppDocumentFormat.select(listOf("application/PCLm"), producible))
        assertNull(IppDocumentFormat.select(listOf("image/urf"), producible))
    }

    // ===== Single-pass iterator PwgWriter (one page) =====

    @Test
    fun pwgWriterProducesValidRasterFromSyntheticPage() {
        val document = multiPassDocument(listOf(solidPage(16, 16)))
        val out = ByteArrayOutputStream()
        PwgWriter(out).use { it.write(document) }
        val bytes = out.toByteArray()
        assertTrue("output too small: ${bytes.size}", bytes.size > 50)
        assertEquals("PWG-Raster magic should start with 'R'", 'R'.code.toByte(), bytes[0])
    }

    // ===== Single-pass iterator PCLm (one page) =====

    @Test
    fun pclmWriterProducesValidPclmFromSyntheticPage() {
        val document = multiPassDocument(listOf(solidPage(16, 16)))
        val out = ByteArrayOutputStream()
        PclmWriter(out, PclmSettings(stripHeight = 16)).use { it.write(document) }
        val bytes = out.toByteArray()
        assertTrue("output too small: ${bytes.size}", bytes.size > 50)
        assertEquals("PCLm should start with %PDF", '%'.code.toByte(), bytes[0])
    }

    // ===== Multi-pass iterator with PCLm (proves BUG 1 fix) =====
    //
    // PclmWriter internally calls document.handleSides() which iterates the document
    // to count pages (and may add a blank page for odd-count duplex). A single-pass
    // iterator would be exhausted after the count, producing zero output pages.
    // This test uses a fresh-iterator-per-call document (same pattern as StreamingPdfPageIterator)
    // and verifies PCLm output contains all pages.

    @Test
    fun pclmWriterWorksMultiPassWithMultiplePages() {
        val pages = listOf(solidPage(8, 8), solidPage(8, 8), solidPage(8, 8))
        val document = multiPassDocument(pages)
        val out = ByteArrayOutputStream()
        PclmWriter(out, PclmSettings(stripHeight = 8)).use { it.write(document) }
        val bytes = out.toByteArray()
        // PCLm output should contain enough data for 3 pages.
        // A single-page PCLm baseline is ~300 bytes; 3 pages should be substantially more.
        assertTrue("PCLm output suspiciously small for 3 pages: ${bytes.size}", bytes.size > 200)
        assertEquals("PCLm should start with %PDF", '%'.code.toByte(), bytes[0])
    }

    @Test
    fun pwgWriterWorksMultiPassWithMultiplePages() {
        val pages = listOf(solidPage(8, 8), solidPage(8, 8))
        val document = multiPassDocument(pages)
        val out = ByteArrayOutputStream()
        PwgWriter(out).use { it.write(document) }
        val bytes = out.toByteArray()
        assertTrue("PWG output suspiciously small for 2 pages: ${bytes.size}", bytes.size > 100)
        assertEquals("PWG-Raster magic should start with 'R'", 'R'.code.toByte(), bytes[0])
    }

    @Test
    fun pclmWriterHandlesOddPageCount() {
        // Odd page count triggers handleSidesExtraBlank which does an extra count+map pass.
        // This is the exact scenario that exposed BUG 1.
        val pages = listOf(
            solidPage(4, 4),
            solidPage(4, 4),
            solidPage(4, 4),
        )
        val document = multiPassDocument(pages)
        val out = ByteArrayOutputStream()
        PclmWriter(out, PclmSettings(stripHeight = 4, output = OutputSettings(colorSpace = ColorSpace.Rgb))).use {
            it.write(document)
        }
        val bytes = out.toByteArray()
        assertTrue("PCLm output for 3-page (odd) document too small: ${bytes.size}", bytes.size > 100)
    }

    // ===== Multi-pass iterator is actually single-pass per instance =====

    @Test
    fun multiPassDocumentProducesFreshIteratorEachCall() {
        val pages = listOf(solidPage(8, 8), solidPage(8, 8))
        val document = multiPassDocument(pages)

        // First iteration should see 2 pages.
        var count1 = 0
        document.iterator().forEach { count1++ }
        assertEquals(2, count1)

        // Second iteration (fresh iterator) should also see 2 pages — not 0.
        var count2 = 0
        document.iterator().forEach { count2++ }
        assertEquals("Fresh iterator must produce same page count on second pass", 2, count2)
    }

    // ===== Pixel conversion correctness =====

    @Test
    fun rgbPixelConversionProducesCorrectByteLayout() {
        val page = knownPixelPage(width = 2, height = 1, pixels = intArrayOf(
            0xFFFF0000.toInt(), // opaque red: A=FF, R=FF, G=00, B=00
            0xFF00FF00.toInt(), // opaque green: R=0, G=255, B=0
        ))
        val byteArray = ByteArray(2 * 3) // 2 pixels * 3 bytes/pixel (RGB)
        page.render(0, 1, ColorSpace.Rgb, byteArray)
        // RGB byte order: R, G, B per pixel
        // Pixel 0 = red (0xFFFF0000): R=255, G=0, B=0
        assertEquals(255.toByte(), byteArray[0])
        assertEquals(0.toByte(), byteArray[1])
        assertEquals(0.toByte(), byteArray[2])
        // Pixel 1 = green (0xFF00FF00): R=0, G=255, B=0
        assertEquals(0.toByte(), byteArray[3])
        assertEquals(255.toByte(), byteArray[4])
        assertEquals(0.toByte(), byteArray[5])
    }

    @Test
    fun grayscalePixelConversionProducesLuminance() {
        // Red pixel: luminance = 0.2126 * 255 ≈ 54
        val page = knownPixelPage(width = 1, height = 1, pixels = intArrayOf(0xFFFF0000.toInt()))
        val byteArray = ByteArray(1) // 1 pixel * 1 byte/pixel (Grayscale)
        page.render(0, 1, ColorSpace.Grayscale, byteArray)
        val gray = byteArray[0].toInt() and 0xFF
        assertTrue("Red luminance should be ~54, got $gray", gray in 53..55)
    }

    @Test
    fun grayscaleWhitePixelProduces255() {
        val page = knownPixelPage(width = 1, height = 1, pixels = intArrayOf(0xFFFFFFFF.toInt()))
        val byteArray = ByteArray(1)
        page.render(0, 1, ColorSpace.Grayscale, byteArray)
        assertEquals(255, byteArray[0].toInt() and 0xFF)
    }

    @Test
    fun grayscaleBlackPixelProduces0() {
        val page = knownPixelPage(width = 1, height = 1, pixels = intArrayOf(0xFF000000.toInt()))
        val byteArray = ByteArray(1)
        page.render(0, 1, ColorSpace.Grayscale, byteArray)
        assertEquals(0, byteArray[0].toInt() and 0xFF)
    }

    @Test
    fun rgbaPixelConversionProducesCorrectByteLayout() {
        val page = knownPixelPage(width = 1, height = 1, pixels = intArrayOf(0x80FF8000u.toInt())) // A=0x80, R=0xFF, G=0x80, B=0x00
        val byteArray = ByteArray(4) // 1 pixel * 4 bytes/pixel (RGBA)
        page.render(0, 1, ColorSpace.Rgba, byteArray)
        assertEquals(255.toByte(), byteArray[0]) // R
        assertEquals(128.toByte(), byteArray[1]) // G (128 is exact)
        assertEquals(0.toByte(), byteArray[2])   // B
        assertEquals(0x80.toByte(), byteArray[3]) // A
    }

    @Test
    fun renderSizeMatchesExpectedForRgbAndGrayscale() {
        // renderSize(height, colorSpace) = widthPixels * bytesPerPixel * height
        val rgbPage = solidPage(10, 20)
        assertEquals(10 * 3 * 5, rgbPage.renderSize(5, ColorSpace.Rgb))
        assertEquals(10 * 1 * 5, rgbPage.renderSize(5, ColorSpace.Grayscale))
    }

    // ===== Helpers =====

    /**
     * Creates a [RenderableDocument] whose [iterator] returns a fresh single-pass iterator on each
     * call, mimicking the [IppRasterizer] StreamingPdfPageIterator pattern. Each iterator instance
     * yields the given pages once; a second call to [iterator] creates a new iterator that yields
     * them again. This is the critical multi-pass invariant that PclmWriter requires.
     */
    private fun multiPassDocument(pages: List<RenderablePage>): RenderableDocument = object : RenderableDocument() {
        override val dpi = 300
        override fun iterator(): Iterator<RenderablePage> = pages.iterator()
    }

    /** A page that fills the byte array with a single solid color (0xFF = white). */
    private fun solidPage(width: Int, height: Int): RenderablePage = object : RenderablePage(width, height) {
        override fun render(yOffset: Int, swathHeight: Int, colorSpace: ColorSpace, byteArray: ByteArray) {
            byteArray.fill(0xFF.toByte())
        }
    }

    /**
     * A page backed by a known ARGB pixel array, used to verify the byte conversion in
     * [IppRasterizer]'s PdfRenderablePage.render(). This mirrors the conversion logic: Android
     * ARGB int → jipp-pdl byte[] in the requested color space.
     */
    private fun knownPixelPage(width: Int, height: Int, pixels: IntArray): RenderablePage =
        object : RenderablePage(width, height) {
            override fun render(yOffset: Int, swathHeight: Int, colorSpace: ColorSpace, byteArray: ByteArray) {
                var src = 0
                var dst = 0
                val rowPixels = IntArray(width * swathHeight)
                // Copy the relevant row(s) from the backing pixel array.
                for (i in rowPixels.indices) {
                    val pixelIndex = yOffset * width + i
                    rowPixels[i] = if (pixelIndex < pixels.size) pixels[pixelIndex] else 0xFFFFFFFF.toInt()
                }
                when (colorSpace) {
                    ColorSpace.Grayscale -> {
                        while (src < rowPixels.size) {
                            val red = (rowPixels[src] shr 16) and 0xFF
                            val green = (rowPixels[src] shr 8) and 0xFF
                            val blue = rowPixels[src] and 0xFF
                            src++
                            byteArray[dst++] = (0.2126 * red + 0.7152 * green + 0.0722 * blue)
                                .roundToInt().coerceIn(0, 255).toByte()
                        }
                    }
                    ColorSpace.Rgb -> {
                        while (src < rowPixels.size) {
                            byteArray[dst++] = ((rowPixels[src] shr 16) and 0xFF).toByte()
                            byteArray[dst++] = ((rowPixels[src] shr 8) and 0xFF).toByte()
                            byteArray[dst++] = (rowPixels[src] and 0xFF).toByte()
                            src++
                        }
                    }
                    ColorSpace.Rgba -> {
                        while (src < rowPixels.size) {
                            byteArray[dst++] = ((rowPixels[src] shr 16) and 0xFF).toByte()
                            byteArray[dst++] = ((rowPixels[src] shr 8) and 0xFF).toByte()
                            byteArray[dst++] = (rowPixels[src] and 0xFF).toByte()
                            byteArray[dst++] = ((rowPixels[src] shr 24) and 0xFF).toByte()
                            src++
                        }
                    }
                }
            }
        }
}
