package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageSizeTest {
    @Test
    fun convertsCommonOriginalSizesToPdfPoints() {
        assertEquals(PdfPageSize(595, 842), ScanDocumentSize.A4.toPdfPageSize())
        assertEquals(PdfPageSize(612, 792), ScanDocumentSize.Letter.toPdfPageSize())
        assertEquals(PdfPageSize(288, 432), ScanDocumentSize.Photo4x6.toPdfPageSize())
    }

    @Test
    fun autoUsesLegacyLetterFallback() {
        assertEquals(PdfPageSize(612, 792), ScanDocumentSize.Auto.toPdfPageSize())
    }
}
