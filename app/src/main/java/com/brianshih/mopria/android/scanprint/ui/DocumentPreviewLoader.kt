package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a bounded preview bitmap without decoding an original scan at full resolution. */
internal object DocumentPreviewLoader {
    suspend fun load(
        context: Context,
        page: DocumentPage,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        DocumentPageBitmapLoader.load(context, page, requestedWidth, requestedHeight)
    }
}
