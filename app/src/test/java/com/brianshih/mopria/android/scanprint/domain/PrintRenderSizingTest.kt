package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintRenderSizingTest {

    @Test
    fun convertsLetterPointsToPrintResolutionPixels() {
        val size = PrintRenderSizing.page(
            widthPoints = 612,
            heightPoints = 792,
            marginPoints = 36,
            requestedDpi = 300,
        )

        assertEquals(300, size.dpi)
        assertEquals(2550, size.pageWidthPixels)
        assertEquals(3300, size.pageHeightPixels)
        assertEquals(150, size.marginPixels)
        assertEquals(2250, size.contentWidthPixels)
        assertEquals(3000, size.contentHeightPixels)
    }

    @Test
    fun capsSourceBitmapBudgetAtThreeHundredDpi() {
        val size = PrintRenderSizing.page(612, 792, 36, requestedDpi = 600)

        assertEquals(PrintRenderSizing.MAX_SOURCE_DPI, size.dpi)
        assertEquals(2550, size.pageWidthPixels)
        assertEquals(3300, size.pageHeightPixels)
    }
}
