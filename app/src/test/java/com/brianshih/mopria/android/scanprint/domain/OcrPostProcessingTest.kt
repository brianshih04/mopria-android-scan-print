package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPostProcessingTest {
    @Test
    fun searchableTextRequiresANonEmptyLineWithValidBounds() {
        assertFalse(OcrResult.Applied("text").hasPositionedText())
        assertFalse(
            OcrResult.Applied(
                text = "text",
                layout = OcrTextLayout(
                    blocks = listOf(
                        OcrTextBlock(
                            text = "text",
                            bounds = null,
                            lines = listOf(OcrTextLine("text", OcrBounds(10, 10, 10, 20))),
                        ),
                    ),
                ),
            ).hasPositionedText(),
        )
        assertTrue(
            OcrResult.Applied(
                text = "text",
                layout = OcrTextLayout(
                    blocks = listOf(
                        OcrTextBlock(
                            text = "text",
                            bounds = null,
                            lines = listOf(OcrTextLine("text", OcrBounds(10, 10, 50, 20))),
                        ),
                    ),
                ),
            ).hasPositionedText(),
        )
    }

    @Test
    fun normalizesGroupedNumberSeparatorsWithoutChangingDecimals() {
        assertEquals("42,937,454", OcrTextNormalizer.normalize("42.937.454"))
        assertEquals("-6,069,700", OcrTextNormalizer.normalize("-6.069,700"))
        assertEquals("7,295,605", OcrTextNormalizer.normalize("7,295.605"))
        assertEquals("-394,317,643", OcrTextNormalizer.normalize("-394.317,643"))
        assertEquals("1,559,431", OcrTextNormalizer.normalize("1,559,43 1"))
        assertEquals("4.42%", OcrTextNormalizer.normalize("4.42%"))
    }

    @Test
    fun normalizesFullWidthNumericPunctuation() {
        assertEquals("-33,995,405", OcrTextNormalizer.normalize("－３３，９９５，４０５"))
    }

    @Test
    fun numberNormalizationNeverJoinsSeparateLines() {
        assertEquals("123\n456", OcrTextNormalizer.normalize("123\n456"))
    }

    @Test
    fun numberNormalizationNeverJoinsAdjacentTableColumns() {
        assertEquals("20,000 9.34%", OcrTextNormalizer.normalize("20,000 9.34%"))
        assertEquals("123 456", OcrTextNormalizer.normalize("123 456"))
    }

    @Test
    fun ordersStrongTwoColumnLayoutByColumnThenLine() {
        fun line(text: String, left: Int, top: Int) = OcrTextLine(
            text = text,
            bounds = OcrBounds(left, top, left + 100, top + 20),
        )

        val layout = OcrTextLayout(
            blocks = listOf(
                OcrTextBlock(
                    text = "",
                    bounds = null,
                    lines = listOf(
                        line("右二", 700, 160),
                        line("左二", 100, 160),
                        line("右一", 700, 80),
                        line("左一", 100, 80),
                        line("右三", 700, 240),
                        line("左三", 100, 240),
                    ),
                ),
            ),
            imageWidth = 1000,
            imageHeight = 1000,
        )

        assertEquals("左一\n左二\n左三\n右一\n右二\n右三", OcrTextFormatter.format(layout))
    }

    @Test
    fun formatsWideTableRowsWithColumnSeparators() {
        fun line(text: String, left: Int, top: Int) = OcrTextLine(
            text = text,
            bounds = OcrBounds(left, top, left + 100, top + 20),
        )

        val layout = OcrTextLayout(
            blocks = listOf(
                OcrTextBlock(
                    text = "",
                    bounds = null,
                    lines = listOf(
                        line("項目", 10, 10),
                        line("合計", 500, 10),
                        line("百分比%", 900, 10),
                        line("營業收入", 10, 80),
                        line("14.694.128", 500, 80),
                        line("100%", 900, 80),
                    ),
                ),
            ),
            imageWidth = 1200,
            imageHeight = 800,
        )

        assertEquals(
            "項目\t合計\t百分比%\n營業收入\t14,694,128\t100%",
            OcrTextFormatter.format(layout),
        )
    }
}
