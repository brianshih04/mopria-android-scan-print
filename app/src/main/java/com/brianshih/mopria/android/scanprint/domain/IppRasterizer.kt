package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.hp.jipp.pdl.ColorSpace
import com.hp.jipp.pdl.OutputSettings
import com.hp.jipp.pdl.RenderableDocument
import com.hp.jipp.pdl.RenderablePage
import com.hp.jipp.pdl.pclm.PclmSettings
import com.hp.jipp.pdl.pclm.PclmWriter
import com.hp.jipp.pdl.pwg.PwgSettings
import com.hp.jipp.pdl.pwg.PwgWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Renders a PDF to PWG-Raster (the IPP Everywhere raster format) via jipp-pdl, driven by an Android
 * [PdfRenderer]-backed [RenderablePage]. Used when an IPP printer does not accept PDF directly.
 *
 * Note: the on-device raster path can only be fully validated against a real printer. The PwgWriter
 * integration itself is covered by [IppRasterizerTest] using a synthetic page (no Android needed).
 *
 * Memory: pages are streamed one at a time — each page's bitmap is allocated, rendered, consumed by
 * the writer, and recycled before the next page begins. Peak memory is one page's ARGB bitmap
 * (e.g. ~32 MB for US Letter @ 300 dpi), not N pages simultaneously.
 *
 * Multi-pass: PclmWriter internally calls [RenderableDocument.handleSides] which may iterate the
 * document more than once (count pages, add blank page for duplex). To support this, each call to
 * the document's [RenderableDocument.iterator] creates a fresh [StreamingPdfPageIterator] that
 * re-renders from page 0 — the [PdfRenderer] stays open during the entire rasterization.
 */
object IppRasterizer {

    /** Rasterize [pdf] to a PWG-Raster file at [dpi] in [colorSpace]; page size follows each PDF page. */
    fun rasterizeToPwgRaster(pdf: File, dpi: Int, colorSpace: ColorSpace): File =
        rasterize(pdf, "pwg", dpi) { document, out ->
            PwgWriter(out, PwgSettings(output = OutputSettings(colorSpace = colorSpace))).use { it.write(document) }
        }

    /** Rasterize [pdf] to a PCLm file (a PDF subset) at [dpi] in [colorSpace] with [stripHeight]. */
    fun rasterizeToPclm(pdf: File, dpi: Int, colorSpace: ColorSpace, stripHeight: Int): File =
        rasterize(pdf, "pclm", dpi) { document, out ->
            PclmWriter(out, PclmSettings(output = OutputSettings(colorSpace = colorSpace), stripHeight = stripHeight)).use { it.write(document) }
        }

    /**
     * Open [pdf], render each page to a [RenderablePage] at [dpi], and let [write] emit it to [out].
     *
     * The document's [iterator][RenderableDocument.iterator] creates a new [StreamingPdfPageIterator]
     * on each call, so writers that iterate multiple times (e.g. PclmWriter for page counting) work
     * correctly. Each iterator renders one page at a time and recycles its bitmap before opening the
     * next, bounding peak memory to a single page.
     */
    private inline fun rasterize(pdf: File, ext: String, dpi: Int, write: (RenderableDocument, OutputStream) -> Unit): File {
        val output = File(pdf.parentFile, "${pdf.nameWithoutExtension}.$ext")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val document = object : RenderableDocument() {
                    override val dpi = dpi
                    override fun iterator(): Iterator<RenderablePage> = StreamingPdfPageIterator(renderer, dpi)
                }
                try {
                    FileOutputStream(output).use { out -> write(document, out) }
                } catch (error: Throwable) {
                    output.delete()
                    throw error
                }
            }
        }
        return output
    }

    internal const val POINTS_PER_INCH = 72.0
}

/**
 * Iterator that streams [PdfRenderablePage] instances one at a time from a [PdfRenderer].
 *
 * When [next] is called, the previous page (if any) is released — its bitmap is recycled and its
 * [PdfRenderer.Page] is closed — before the next page is opened and rendered. This bounds peak
 * memory to a single page's bitmap regardless of document length.
 *
 * Each instance is single-pass. Callers that need to iterate multiple times (e.g. PclmWriter's
 * internal page counting) should create a new instance via the [RenderableDocument.iterator] override.
 */
private class StreamingPdfPageIterator(
    private val renderer: PdfRenderer,
    private val dpi: Int,
) : Iterator<RenderablePage> {
    private var index = 0
    private var currentPage: PdfRenderablePage? = null

    override fun hasNext(): Boolean = index < renderer.pageCount

    override fun next(): RenderablePage {
        if (!hasNext()) throw NoSuchElementException("No more PDF pages")
        // Release the previous page before opening the next.
        currentPage?.release()
        renderer.openPage(index).use { page ->
            val widthPixels = (page.width / IppRasterizer.POINTS_PER_INCH * dpi).roundToInt().coerceAtLeast(1)
            val heightPixels = (page.height / IppRasterizer.POINTS_PER_INCH * dpi).roundToInt().coerceAtLeast(1)
            val renderable = PdfRenderablePage(page, widthPixels, heightPixels)
            currentPage = renderable
            index++
            return renderable
        }
    }
}

/**
 * A [RenderablePage] backed by a pre-rendered ARGB bitmap of one PDF page.
 *
 * The bitmap is created eagerly in the constructor (the [page] is rendered immediately and then
 * closed by the caller), so the page content is available for all subsequent [render] swath calls.
 * Call [release] to recycle the bitmap when the writer moves to the next page.
 */
private class PdfRenderablePage(
    page: PdfRenderer.Page,
    widthPixels: Int,
    heightPixels: Int,
) : RenderablePage(widthPixels, heightPixels) {

    private val bitmap = createBitmap(widthPixels, heightPixels).also { bitmap ->
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }

    override fun render(yOffset: Int, swathHeight: Int, colorSpace: ColorSpace, byteArray: ByteArray) {
        val pixels = IntArray(widthPixels * swathHeight)
        bitmap.getPixels(pixels, 0, widthPixels, 0, yOffset, widthPixels, swathHeight)
        var src = 0
        var dst = 0
        when (colorSpace) {
            ColorSpace.Grayscale -> {
                while (src < pixels.size) {
                    val red = (pixels[src] shr 16) and 0xFF
                    val green = (pixels[src] shr 8) and 0xFF
                    val blue = pixels[src] and 0xFF
                    src++
                    byteArray[dst++] = (RED_LUMA * red + GREEN_LUMA * green + BLUE_LUMA * blue)
                        .roundToInt().coerceIn(0, 255).toByte()
                }
            }
            ColorSpace.Rgb -> {
                while (src < pixels.size) {
                    byteArray[dst++] = ((pixels[src] shr 16) and 0xFF).toByte()
                    byteArray[dst++] = ((pixels[src] shr 8) and 0xFF).toByte()
                    byteArray[dst++] = (pixels[src] and 0xFF).toByte()
                    src++
                }
            }
            ColorSpace.Rgba -> {
                while (src < pixels.size) {
                    byteArray[dst++] = ((pixels[src] shr 16) and 0xFF).toByte()
                    byteArray[dst++] = ((pixels[src] shr 8) and 0xFF).toByte()
                    byteArray[dst++] = (pixels[src] and 0xFF).toByte()
                    byteArray[dst++] = ((pixels[src] shr 24) and 0xFF).toByte()
                    src++
                }
            }
        }
    }

    fun release() = bitmap.recycle()

    private companion object {
        private const val RED_LUMA = 0.2126
        private const val GREEN_LUMA = 0.7152
        private const val BLUE_LUMA = 0.0722
    }
}
