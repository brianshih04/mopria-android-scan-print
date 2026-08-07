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
 * Memory: each page is rendered to a full ARGB bitmap at (pageInches × [dpi]), so very large or very
 * high-dpi multi-page jobs may be heavy on low-RAM devices; streaming swaths is a future refinement.
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

    /** Open [pdf], render each page to a [RenderablePage] at [dpi], and let [write] emit it to [out]. */
    private inline fun rasterize(pdf: File, ext: String, dpi: Int, write: (RenderableDocument, OutputStream) -> Unit): File {
        val output = File(pdf.parentFile, "${pdf.nameWithoutExtension}.$ext")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pages = (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        PdfRenderablePage(
                            page,
                            widthPixels = (page.width / POINTS_PER_INCH * dpi).roundToInt().coerceAtLeast(1),
                            heightPixels = (page.height / POINTS_PER_INCH * dpi).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
                try {
                    val document = object : RenderableDocument() {
                        override val dpi = dpi
                        override fun iterator() = pages.iterator()
                    }
                    FileOutputStream(output).use { out -> write(document, out) }
                } finally {
                    pages.forEach { it.release() }
                }
            }
        }
        return output
    }

    private const val POINTS_PER_INCH = 72.0
}

/** A [RenderablePage] backed by a pre-rendered ARGB bitmap of one PDF page. */
private class PdfRenderablePage(
    page: PdfRenderer.Page,
    widthPixels: Int,
    heightPixels: Int,
) : RenderablePage(widthPixels, heightPixels) {

    private val bitmap = createBitmap(widthPixels, heightPixels).also { bitmap ->
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    override fun render(yOffset: Int, swathHeight: Int, colorSpace: ColorSpace, byteArray: ByteArray) {
        val pixels = IntArray(widthPixels * swathHeight)
        bitmap.getPixels(pixels, 0, widthPixels, 0, yOffset, widthPixels, swathHeight)
        var src = 0
        var dst = 0
        while (src < pixels.size) {
            val red = (pixels[src] shr 16) and 0xFF
            val green = (pixels[src] shr 8) and 0xFF
            val blue = pixels[src] and 0xFF
            src++
            if (colorSpace == ColorSpace.Grayscale) {
                byteArray[dst++] = (RED_LUMA * red + GREEN_LUMA * green + BLUE_LUMA * blue).toInt().toByte()
            } else {
                byteArray[dst++] = red.toByte()
                byteArray[dst++] = green.toByte()
                byteArray[dst++] = blue.toByte()
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
