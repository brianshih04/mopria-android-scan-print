package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SystemPrintAdapter(
    private val document: MopriaDocument,
    private val context: Context,
) : PrintDocumentAdapter() {
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pageWidth = PAGE_WIDTH

    @Volatile
    private var pageHeight = PAGE_HEIGHT

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

        newAttributes.mediaSize?.let { mediaSize ->
            pageWidth = max(1, (mediaSize.widthMils / 1000f * POINTS_PER_INCH).roundToInt())
            pageHeight = max(1, (mediaSize.heightMils / 1000f * POINTS_PER_INCH).roundToInt())
        }
        val info = PrintDocumentInfo.Builder(document.displayName(context))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(document.pages.size)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal.isCanceled) {
            runCatching { destination.close() }
            callback.onWriteCancelled()
            return
        }

        workerScope.launch {
            val pdf = PdfDocument()
            try {
                var outputPage = 1
                document.pages.forEachIndexed { index, pageData ->
                    if (cancellationSignal.isCanceled) throw CancellationException("Print cancelled")
                    if (!isPageRequested(index, pages)) return@forEachIndexed
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, outputPage).create()
                    val page = pdf.startPage(pageInfo)
                    drawPage(page.canvas, document, pageData)
                    pdf.finishPage(page)
                    outputPage += 1
                }
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output -> pdf.writeTo(output) }
                if (cancellationSignal.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (_: CancellationException) {
                runCatching { destination.close() }
                callback.onWriteCancelled()
            } catch (error: Exception) {
                runCatching { destination.close() }
                callback.onWriteFailed(error.message ?: context.getString(R.string.print_create_failed))
            } finally {
                pdf.close()
            }
        }
    }

    override fun onFinish() {
        workerScope.cancel()
        super.onFinish()
    }

    private fun drawPage(canvas: Canvas, document: MopriaDocument, page: DocumentPage) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 54, 78)
            textSize = 26f
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 16f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 195, 215)
            strokeWidth = 2f
        }
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val margin = width * 0.088f
        canvas.drawColor(Color.WHITE)
        val localized = LanguageManager.wrap(context)
        canvas.drawText(context.getString(R.string.app_name), margin, height * 0.096f, titlePaint)
        canvas.drawText(document.displayName(context), margin, height * 0.141f, bodyPaint)
        canvas.drawLine(margin, height * 0.177f, width - margin, height * 0.177f, linePaint)
        val bitmap = DocumentPageBitmapLoader.load(context, page, requestedWidth = 2048, requestedHeight = 2048)
        if (bitmap != null) {
            try {
                val top = height * 0.20f
                val bottom = height * 0.79f
                val scale = min(
                    (width - margin * 2) / bitmap.width.toFloat(),
                    (bottom - top) / bitmap.height.toFloat(),
                )
                val renderedWidth = bitmap.width * scale
                val renderedHeight = bitmap.height * scale
                val left = (width - renderedWidth) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, top, left + renderedWidth, top + renderedHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                canvas.drawText("${page.pageNumber}. ${page.displayTitle(context)}", margin, height * 0.85f, titlePaint)
            } finally {
                bitmap.recycle()
            }
        } else {
            canvas.drawText("${page.pageNumber}. ${page.displayTitle(context)}", margin, height * 0.24f, titlePaint)
            canvas.drawText(localized.getString(R.string.pdf_mock_saved), margin, height * 0.30f, bodyPaint)
            canvas.drawText(localized.getString(R.string.pdf_mock_fixture), margin, height * 0.34f, bodyPaint)
        }
        canvas.drawText(localized.getString(R.string.pdf_source, document.displaySource(context)), margin, height * 0.89f, bodyPaint)
        canvas.drawText(
            localized.getString(R.string.pdf_page_of, page.pageNumber, document.pages.size),
            margin,
            height * 0.94f,
            bodyPaint,
        )
    }

    private fun isPageRequested(pageIndex: Int, ranges: Array<out PageRange>): Boolean =
        ranges.any { pageIndex in it.start..it.end }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val POINTS_PER_INCH = 72f
    }
}
