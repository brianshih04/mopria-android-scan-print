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


    @Test(expected = IllegalArgumentException::class)
    fun appendFlatbedRejectsEmptyScan() {
        ScanDocumentOrganizer.appendFlatbedPage(null, document("empty", emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitPagesRejectsEmptyDocument() {
        ScanDocumentOrganizer.splitPages(document("empty", emptyList()))
    }

    @Test
    fun appendFlatbedPreservesExistingIdWhenMerging() {
        val existing = document("base", listOf(DocumentPage("p1", 1, "one")))
        val scanned = document("new", listOf(DocumentPage("p2", 1, "two")))
        val merged = ScanDocumentOrganizer.appendFlatbedPage(existing, scanned)
        // When existing is non-null, the base document's id is preserved.
        assertEquals("base", merged.id)
    }

    @Test
    fun splitPagesNamesDocumentsWithOriginalName() {
        val doc = document("adf", listOf(
            DocumentPage("p1", 1, "one"),
            DocumentPage("p2", 2, "two"),
        ))
        val split = ScanDocumentOrganizer.splitPages(doc)
        assertEquals("Document - page 1", split[0].name)
        assertEquals("Document - page 2", split[1].name)
    }

    @Test
    fun appendMultipleFlatbedPagesMaintainsOrder() {
        var doc: MopriaDocument? = null
        for (i in 1..5) {
            val scan = document("scan$i", listOf(DocumentPage("p$i", 1, "page $i")))
            doc = ScanDocumentOrganizer.appendFlatbedPage(doc, scan)
        }
        assertEquals(5, doc!!.pages.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), doc.pages.map(DocumentPage::id))
        assertEquals(listOf(1, 2, 3, 4, 5), doc.pages.map(DocumentPage::pageNumber))
    }

    private fun document(id: String, pages: List<DocumentPage>) = MopriaDocument(
        id = id,
        name = "Document",
        pages = pages,
        sourceLabel = "Scanner · eSCL · Flatbed",
        createdAt = 1234L,
    )
}
