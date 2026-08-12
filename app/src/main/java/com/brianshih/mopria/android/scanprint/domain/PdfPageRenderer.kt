package com.brianshih.mopria.android.scanprint.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Shared PDF page rendering used by both [ScanExportService] (export/share with metadata chrome)
 * and `RealIntegrationProvider` (clean IPP print pages). Consolidates the previously duplicated
 * PdfDocument creation loop, bitmap loading and fit-to-page drawing.
 *
 * Callers provide a [PageDecorator] callback that draws page-specific content (metadata, labels,
 * or nothing for clean print pages) onto each page's [Canvas]. The bitmap loading and aspect-ratio
 * fitting is handled here so both paths stay consistent.
 */
object PdfPageRenderer {

    /** Standard US Letter page size in PDF points (1/72 inch). */
    const val PAGE_WIDTH = 612
    const val PAGE_HEIGHT = 792

    /**
     * Renders [document] to [output] as a multi-page PDF. Each page is [PAGE_WIDTH]×[PAGE_HEIGHT]
     * points; [decoratePage] draws page-specific content after the white background is cleared.
     */
    fun writePdf(document: MopriaDocument, output: OutputStream, decoratePage: PageDecorator) {
        val pdf = PdfDocument()
        val pageSize = document.documentSize?.toPdfPageSize() ?: PdfPageSize(PAGE_WIDTH, PAGE_HEIGHT)
        try {
            document.pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(
                    pageSize.widthPoints,
                    pageSize.heightPoints,
                    index + 1,
                ).create()
                val pdfPage = pdf.startPage(info)
                decoratePage.decorate(pdfPage.canvas, document, page, index + 1)
                pdf.finishPage(pdfPage)
            }
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }

    /**
     * Draws [bitmap] fitted within [margin] on [canvas], preserving aspect ratio.
     * The caller is responsible for recycling [bitmap] after this returns.
     */
    fun drawFitted(canvas: Canvas, bitmap: Bitmap, margin: Float, maxScale: Float = Float.MAX_VALUE) {
        val scale = minOf(
            (canvas.width - margin * 2) / bitmap.width.toFloat(),
            (canvas.height - margin * 2) / bitmap.height.toFloat(),
        ).coerceAtMost(maxScale)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (canvas.width - width) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, margin, left + width, margin + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }

    /**
     * Draws [bitmap] fitted within a custom content area defined by [top] and [bottom] on [canvas].
     * Used when the caller needs the image positioned below a header area.
     */
    fun drawFittedInArea(
        canvas: Canvas,
        bitmap: Bitmap,
        margin: Float,
        top: Float,
        bottom: Float,
    ) {
        val scale = minOf(
            (canvas.width - margin * 2) / bitmap.width.toFloat(),
            (bottom - top) / bitmap.height.toFloat(),
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (canvas.width - width) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }

    /** Callback for drawing page-specific content onto a PDF page [Canvas]. */
    fun interface PageDecorator {
        fun decorate(canvas: Canvas, document: MopriaDocument, page: DocumentPage, pageNumber: Int)
    }
}
