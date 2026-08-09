package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrLayoutTransformsTest {
    @Test
    fun cropAndScaleKeepTextAtTheSameRelativePosition() {
        val transformed = OcrLayoutTransforms.forPage(
            layout = layout(OcrBounds(10, 20, 30, 60), width = 100, height = 200),
            page = page(cropRect = CropRect(0.1f, 0.1f, 0.9f, 0.9f)),
            outputWidth = 80,
            outputHeight = 160,
        )

        val bounds = requireNotNull(transformed).blocks.single().lines.single().bounds
        assertEquals(OcrBounds(0, 0, 20, 40), bounds)
    }

    @Test
    fun clockwiseRotationUsesTheSameTopLeftCoordinateSystemAsBitmapExport() {
        val transformed = OcrLayoutTransforms.forPage(
            layout = layout(OcrBounds(10, 20, 30, 60), width = 100, height = 200),
            page = page(rotationDegrees = 90),
            outputWidth = 200,
            outputHeight = 100,
        )

        val bounds = requireNotNull(transformed).blocks.single().lines.single().bounds
        assertEquals(OcrBounds(140, 10, 180, 30), bounds)
    }

    @Test
    fun missingSourceDimensionsCannotProduceAConfidentTextLayer() {
        val transformed = OcrLayoutTransforms.forPage(
            layout = layout(OcrBounds(1, 1, 2, 2), width = 0, height = 0),
            page = page(),
            outputWidth = 80,
            outputHeight = 160,
        )

        assertNull(transformed)
    }

    private fun layout(bounds: OcrBounds, width: Int, height: Int) = OcrTextLayout(
        blocks = listOf(
            OcrTextBlock(
                text = "sample",
                bounds = bounds,
                lines = listOf(OcrTextLine(text = "sample", bounds = bounds)),
            ),
        ),
        imageWidth = width,
        imageHeight = height,
    )

    private fun page(
        rotationDegrees: Int = 0,
        cropRect: CropRect? = null,
    ) = DocumentPage(
        id = "page",
        pageNumber = 1,
        title = "page",
        rotationDegrees = rotationDegrees,
        cropRect = cropRect,
    )
}
