package com.brianshih.mopria.android.scanprint.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument

class SystemPrintAdapter(
    private val document: MopriaDocument,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(document.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(document.pages.size)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onWriteCancelled()
            return
        }

        val pdf = PdfDocument()
        try {
            document.pages.forEachIndexed { index, pageData ->
                if (cancellationSignal.isCanceled) return@forEachIndexed
                val pageInfo = PdfDocument.PageInfo.Builder(612, 792, index + 1).create()
                val page = pdf.startPage(pageInfo)
                drawPage(page.canvas, document, pageData)
                pdf.finishPage(page)
            }
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> pdf.writeTo(output) }
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        } finally {
            pdf.close()
        }
    }

    private fun drawPage(canvas: Canvas, document: MopriaDocument, page: DocumentPage) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(35, 54, 78)
            textSize = 26f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = 16f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(180, 195, 215)
            strokeWidth = 2f
        }
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText("Mopria Scan & Print", 54f, 76f, titlePaint)
        canvas.drawText(document.name, 54f, 112f, bodyPaint)
        canvas.drawLine(54f, 140f, 558f, 140f, linePaint)
        canvas.drawText("${page.pageNumber}. ${page.title}", 54f, 190f, titlePaint)
        canvas.drawText("System Print Framework preview fixture.", 54f, 236f, bodyPaint)
        canvas.drawText("The real eSCL scan result will replace this fixture.", 54f, 264f, bodyPaint)
        canvas.drawText("Source: ${document.sourceLabel}", 54f, 690f, bodyPaint)
        canvas.drawText("Page ${page.pageNumber} of ${document.pages.size}", 54f, 728f, bodyPaint)
    }
}
