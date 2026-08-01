package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
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
import kotlin.math.min

class SystemPrintAdapter(
    private val document: MopriaDocument,
    private val context: Context? = null,
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
        val bitmap = loadBitmap(page.imagePath)
        if (bitmap != null) {
            val margin = 54f
            val top = 158f
            val bottom = 640f
            val scale = min(
                (canvas.width - margin * 2) / bitmap.width.toFloat(),
                (bottom - top) / bitmap.height.toFloat(),
            )
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val left = (canvas.width - width) / 2f
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.drawText("${page.pageNumber}. ${page.title}", margin, 684f, titlePaint)
            bitmap.recycle()
        } else {
            canvas.drawText("${page.pageNumber}. ${page.title}", 54f, 190f, titlePaint)
            canvas.drawText("System Print Framework preview fixture.", 54f, 236f, bodyPaint)
            canvas.drawText("The real eSCL scan result will replace this fixture.", 54f, 264f, bodyPaint)
        }
        canvas.drawText("Source: ${document.sourceLabel}", 54f, 690f, bodyPaint)
        canvas.drawText("Page ${page.pageNumber} of ${document.pages.size}", 54f, 728f, bodyPaint)
    }

    private fun loadBitmap(path: String?): android.graphics.Bitmap? {
        if (path == null) return null
        return if (path.startsWith("content://") && context != null) {
            context.contentResolver.openInputStream(Uri.parse(path))?.use(BitmapFactory::decodeStream)
        } else {
            BitmapFactory.decodeFile(path)
        }
    }
}
