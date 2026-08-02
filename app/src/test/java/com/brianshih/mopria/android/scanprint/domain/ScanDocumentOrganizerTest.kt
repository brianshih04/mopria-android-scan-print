package com.brianshih.mopria.android.scanprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDocumentOrganizerTest {
    @Test
    fun appendsFlatbedJobsIntoOneOrderedDocument() {
        val first = document("first", listOf(DocumentPage("p1", 1, "one")))
        val second = document("second", listOf(DocumentPage("p2", 1, "two")))

        val batch = ScanDocumentOrganizer.appendFlatbedPage(null, first)
        val merged = ScanDocumentOrganizer.appendFlatbedPage(batch, second)

        assertEquals("first", merged.id)
        assertEquals(listOf(1, 2), merged.pages.map(DocumentPage::pageNumber))
        assertEquals(listOf("p1", "p2"), merged.pages.map(DocumentPage::id))
        assertTrue(merged.sourceLabel.endsWith("Multi-page PDF"))
    }

    @Test
    fun splitsAdfPagesIntoIndependentDocumentsWhenPdfMergeIsDisabled() {
        val pages = listOf(DocumentPage("p1", 1, "one"), DocumentPage("p2", 2, "two"))
        val split = ScanDocumentOrganizer.splitPages(document("adf", pages))

        assertEquals(2, split.size)
        assertEquals(listOf("adf-page-1", "adf-page-2"), split.map(MopriaDocument::id))
        assertTrue(split.all { it.pages.single().pageNumber == 1 })
    }

    private fun document(id: String, pages: List<DocumentPage>) = MopriaDocument(
        id = id,
        name = "Document",
        pages = pages,
        sourceLabel = "Scanner · eSCL · Flatbed",
        createdAt = 1234L,
    )
}
