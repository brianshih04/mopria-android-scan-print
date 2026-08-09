package com.brianshih.mopria.android.scanprint.domain

import kotlin.math.max
import kotlin.math.roundToInt

/** Maps ML Kit top-left image coordinates through the page edits used at export time. */
object OcrLayoutTransforms {
    /**
     * Applies the same crop-then-rotate operations as [DocumentPageBitmapLoader] and scales the
     * result to the bounded bitmap that will be embedded in the PDF.
     */
    fun forPage(
        layout: OcrTextLayout,
        page: DocumentPage,
        outputWidth: Int,
        outputHeight: Int,
    ): OcrTextLayout? {
        val sourceWidth = layout.imageWidth
        val sourceHeight = layout.imageHeight
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return null

        val rotation = ((page.rotationDegrees % 360) + 360) % 360
        if (rotation !in setOf(0, 90, 180, 270)) return null

        val crop = page.cropRect
        val cropLeft = crop?.left?.times(sourceWidth) ?: 0f
        val cropTop = crop?.top?.times(sourceHeight) ?: 0f
        val cropRight = crop?.right?.times(sourceWidth) ?: sourceWidth.toFloat()
        val cropBottom = crop?.bottom?.times(sourceHeight) ?: sourceHeight.toFloat()
        val cropWidth = (cropRight - cropLeft).coerceAtLeast(1f)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1f)
        val transformedWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val transformedHeight = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
        val scaleX = outputWidth / transformedWidth
        val scaleY = outputHeight / transformedHeight

        fun mapPoint(x: Float, y: Float): PointF {
            val localX = (x - cropLeft).coerceIn(0f, cropWidth)
            val localY = (y - cropTop).coerceIn(0f, cropHeight)
            return when (rotation) {
                0 -> PointF(localX, localY)
                90 -> PointF(cropHeight - localY, localX)
                180 -> PointF(cropWidth - localX, cropHeight - localY)
                else -> PointF(localY, cropWidth - localX)
            }
        }

        fun bounds(value: OcrBounds?): OcrBounds? {
            if (value == null || value.width <= 0 || value.height <= 0) return null
            val corners = listOf(
                mapPoint(value.left.toFloat(), value.top.toFloat()),
                mapPoint(value.right.toFloat(), value.top.toFloat()),
                mapPoint(value.right.toFloat(), value.bottom.toFloat()),
                mapPoint(value.left.toFloat(), value.bottom.toFloat()),
            )
            return OcrBounds(
                left = scaled(corners.minOf { it.x }, scaleX, outputWidth),
                top = scaled(corners.minOf { it.y }, scaleY, outputHeight),
                right = scaled(corners.maxOf { it.x }, scaleX, outputWidth),
                bottom = scaled(corners.maxOf { it.y }, scaleY, outputHeight),
            ).takeIf { it.width > 0 && it.height > 0 }
        }

        fun points(values: List<OcrPoint>): List<OcrPoint> = values.map { point ->
            val mapped = mapPoint(point.x.toFloat(), point.y.toFloat())
            OcrPoint(
                x = scaled(mapped.x, scaleX, outputWidth),
                y = scaled(mapped.y, scaleY, outputHeight),
            )
        }

        fun symbol(value: OcrTextSymbol): OcrTextSymbol = value.copy(
            bounds = bounds(value.bounds),
            cornerPoints = points(value.cornerPoints),
        )

        fun element(value: OcrTextElement): OcrTextElement = value.copy(
            bounds = bounds(value.bounds),
            cornerPoints = points(value.cornerPoints),
            symbols = value.symbols.map(::symbol),
        )

        fun line(value: OcrTextLine): OcrTextLine = value.copy(
            bounds = bounds(value.bounds),
            cornerPoints = points(value.cornerPoints),
            elements = value.elements.map(::element),
        )

        fun block(value: OcrTextBlock): OcrTextBlock = value.copy(
            bounds = bounds(value.bounds),
            cornerPoints = points(value.cornerPoints),
            lines = value.lines.map(::line),
        )

        return OcrTextLayout(
            blocks = layout.blocks.map(::block),
            imageWidth = outputWidth,
            imageHeight = outputHeight,
        )
    }

    private fun scaled(value: Float, scale: Float, limit: Int): Int =
        (value * scale).roundToInt().coerceIn(0, max(0, limit))

    private data class PointF(val x: Float, val y: Float)
}
