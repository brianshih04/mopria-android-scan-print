package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.DocumentPage

internal fun MopriaDocument.displayName(context: Context): String = generatedName?.let { generated ->
    LanguageManager.wrap(context).getString(generated.labelRes, generatedNameSuffix.orEmpty())
} ?: name

internal fun DocumentPage.displayTitle(context: Context): String = generatedTitle?.let { generated ->
    generatedTitleNumber?.let { number -> LanguageManager.wrap(context).getString(generated.labelRes, number) }
        ?: LanguageManager.wrap(context).getString(generated.labelRes)
} ?: title

internal fun MopriaDocument.displaySource(context: Context): String = if (actualScanSettingsReported) {
    sourceLabel
} else {
    "$sourceLabel · ${LanguageManager.wrap(context).getString(R.string.document_requested_settings_note)}"
}
