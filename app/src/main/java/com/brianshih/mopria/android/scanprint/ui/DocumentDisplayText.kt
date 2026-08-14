package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.DocumentPage

internal fun MopriaDocument.displayName(context: Context): String {
    val base = generatedName?.let { generated ->
        LanguageManager.wrap(context).getString(generated.labelRes, generatedNameSuffix.orEmpty())
    } ?: name
    return generatedNamePageNumber?.let { page ->
        base + LanguageManager.wrap(context).getString(R.string.document_name_page_suffix, page)
    } ?: base
}

internal fun DocumentPage.displayTitle(context: Context): String = generatedTitle?.let { generated ->
    generatedTitleNumber?.let { number -> LanguageManager.wrap(context).getString(generated.labelRes, number) }
        ?: LanguageManager.wrap(context).getString(generated.labelRes)
} ?: title

internal fun MopriaDocument.displaySource(context: Context): String {
    val labels = LanguageManager.wrap(context)
    // The persisted sourceLabel carries "%" tokens from the domain layer; legacy records may still
    // contain raw eSCL keywords, so both forms map to localized labels. Scanner names and dpi values
    // pass through unchanged.
    val localized = sourceLabel.split(" · ").joinToString(" · ") { segment ->
        when (segment.trim()) {
            "%flatbed", "Platen" -> labels.getString(R.string.source_flatbed_short)
            "%adf", "Feeder" -> labels.getString(R.string.source_adf_short)
            "%color", "RGB24" -> labels.getString(R.string.color_color)
            "%grayscale", "Grayscale8" -> labels.getString(R.string.color_grayscale)
            "%blackwhite", "BlackAndWhite1" -> labels.getString(R.string.color_black_white)
            "%multipage", "Multi-page PDF" -> labels.getString(R.string.document_multi_page_pdf)
            "%mock", "Mock eSCL" -> labels.getString(R.string.document_source_mock_escl)
            else -> segment
        }
    }
    return if (actualScanSettingsReported) {
        localized
    } else {
        "$localized · ${labels.getString(R.string.document_requested_settings_note)}"
    }
}
