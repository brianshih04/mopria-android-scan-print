package com.brianshih.mopria.android.scanprint.domain

/** Pure document assembly rules shared by mock and real scan workflows. */
object ScanDocumentOrganizer {
    fun appendFlatbedPage(existing: MopriaDocument?, scanned: MopriaDocument): MopriaDocument {
        require(scanned.pages.isNotEmpty()) { "Flatbed scan has no pages to combine" }
        val base = existing ?: scanned.copy(
            name = "Flatbed multi-page document ${scanned.createdAt.toString().takeLast(4)}",
            generatedName = GeneratedDocumentName.FlatbedMultiPage,
            generatedNameSuffix = scanned.createdAt.toString().takeLast(4),
        )
        val combinedPages = (existing?.pages.orEmpty() + scanned.pages).mapIndexed { index, page ->
            page.copy(
                pageNumber = index + 1,
                title = "Scanned page ${index + 1}",
                generatedTitle = GeneratedPageTitle.Scanned,
                generatedTitleNumber = index + 1,
            )
        }
        return base.copy(
            sourceLabel = "${scanned.sourceLabel.substringBeforeLast(" · %multipage")} · %multipage",
            pages = combinedPages,
            searchablePdf = base.searchablePdf || scanned.searchablePdf,
        )
    }

    fun splitPages(document: MopriaDocument): List<MopriaDocument> {
        require(document.pages.isNotEmpty()) { "ADF scan has no pages to split" }
        return document.pages.mapIndexed { index, page ->
            document.copy(
                id = "${document.id}-page-${index + 1}",
                name = "${document.name} - page ${index + 1}",
                generatedNamePageNumber = index + 1,
                pages = listOf(
                    page.copy(
                        pageNumber = 1,
                        title = "Scanned page 1",
                        generatedTitle = GeneratedPageTitle.Scanned,
                        generatedTitleNumber = 1,
                    ),
                ),
            )
        }
    }
}
