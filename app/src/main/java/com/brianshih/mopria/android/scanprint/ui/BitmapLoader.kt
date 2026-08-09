package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import com.brianshih.mopria.android.scanprint.domain.SampledBitmapDecoder

/** Decodes only the resolution needed by PDF/export surfaces to avoid full-size image OOMs. */
internal object BitmapLoader {
    fun load(context: Context?, path: String?, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("content://") -> context?.let {
                load(it, path.toUri(), requestedWidth, requestedHeight)
            }
            path.startsWith("file://") -> SampledBitmapDecoder.decodeFile(path.toUri().path ?: "", requestedWidth, requestedHeight)
            else -> SampledBitmapDecoder.decodeFile(path, requestedWidth, requestedHeight)
        }
    }

    fun load(context: Context, uri: Uri, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = SampledBitmapDecoder.sampleSize(bounds.outWidth, bounds.outHeight, requestedWidth, requestedHeight)
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}
