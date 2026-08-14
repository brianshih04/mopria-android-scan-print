package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.CropCoordinateMapper
import com.brianshih.mopria.android.scanprint.domain.CropRect
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditScreen(
    document: MopriaDocument,
    onRotatePage: (String, Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onCropPage: (String, CropRect?) -> Unit,
    onBack: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var cropTarget by remember { mutableStateOf<DocumentPage?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResourceSafe(R.string.edit_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        if (document.pages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResourceSafe(R.string.edit_no_pages), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(document.pages, key = { _, page -> page.id }) { index, page ->
                    EditablePageCard(
                        page = page,
                        canMoveLeft = index > 0,
                        canMoveRight = index < document.pages.lastIndex,
                        onRotateLeft = { onRotatePage(page.id, 270) },
                        onRotateRight = { onRotatePage(page.id, 90) },
                        onMoveLeft = { onMovePage(index, index - 1) },
                        onMoveRight = { onMovePage(index, index + 1) },
                        onCrop = { cropTarget = page },
                        onDelete = { deleteTarget = page.id },
                    )
                }
            }
        }
    }

    deleteTarget?.let { pageId ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResourceSafe(R.string.edit_delete_page)) },
            text = { Text(stringResourceSafe(R.string.edit_delete_page_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePage(pageId)
                    deleteTarget = null
                }) {
                    Text(stringResourceSafe(R.string.edit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResourceSafe(R.string.edit_cancel))
                }
            },
        )
    }
    cropTarget?.let { page ->
        CropDialog(
            page = page,
            onConfirm = { crop ->
                onCropPage(page.id, crop)
                cropTarget = null
            },
            onClear = {
                onCropPage(page.id, null)
                cropTarget = null
            },
            onDismiss = { cropTarget = null },
        )
    }
}

@Composable
private fun EditablePageCard(
    page: DocumentPage,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onCrop: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail with rotation
            val result by produceState<Result<android.graphics.Bitmap?>?>(
                initialValue = null,
                key1 = page.id,
                key2 = page.rotationDegrees,
                key3 = page.cropRect,
            ) {
                value = runCatching { DocumentPreviewLoader.load(context, page, 200, 280) }
            }
            val bitmap = result?.getOrNull()
            Surface(
                modifier = Modifier.size(72.dp, 100.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // Page number + actions
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${page.pageNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onRotateLeft) {
                        Icon(Icons.Outlined.RotateLeft, contentDescription = null)
                    }
                    IconButton(onClick = onRotateRight) {
                        Icon(Icons.Outlined.RotateRight, contentDescription = null)
                    }
                    IconButton(onClick = onCrop) {
                        Icon(Icons.Outlined.Crop, contentDescription = null)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onMoveLeft, enabled = canMoveLeft) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
                    }
                    IconButton(onClick = onMoveRight, enabled = canMoveRight) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** Avoids name clash with Compose's stringResource in certain import configurations. */
@Composable
private fun stringResourceSafe(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

@Composable
private fun CropDialog(
    page: DocumentPage,
    onConfirm: (CropRect) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val result by produceState<Result<android.graphics.Bitmap?>?>(
        initialValue = null,
        key1 = page.id,
        key2 = page.rotationDegrees,
    ) {
        // Show the uncropped source while the sliders define the crop fractions.
        value = runCatching { DocumentPreviewLoader.load(context, page.copy(cropRect = null), 600, 800) }
    }
    val bitmap = result?.getOrNull()
    val displayedCrop = remember(page.cropRect, page.rotationDegrees) {
        page.cropRect?.let { CropCoordinateMapper.sourceToDisplay(it, page.rotationDegrees) }
    }
    var left by remember(displayedCrop) { mutableFloatStateOf(displayedCrop?.left ?: 0.05f) }
    var top by remember(displayedCrop) { mutableFloatStateOf(displayedCrop?.top ?: 0.05f) }
    var right by remember(displayedCrop) { mutableFloatStateOf(displayedCrop?.right ?: 0.95f) }
    var bottom by remember(displayedCrop) { mutableFloatStateOf(displayedCrop?.bottom ?: 0.95f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResourceSafe(R.string.edit_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (bitmap != null) {
                    val bmpImage = bitmap.asImageBitmap()
                    Box(
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bmpImage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                            val w = bitmap.width * scale
                            val h = bitmap.height * scale
                            val imageLeft = (size.width - w) / 2f
                            val imageTop = (size.height - h) / 2f
                            val cLeft = imageLeft + left * w
                            val cTop = imageTop + top * h
                            val cRight = imageLeft + right * w
                            val cBottom = imageTop + bottom * h
                            val overlay = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)
                            drawRect(overlay, topLeft = Offset(imageLeft, imageTop), size = Size(w, cTop - imageTop))
                            drawRect(overlay, topLeft = Offset(imageLeft, cBottom), size = Size(w, imageTop + h - cBottom))
                            drawRect(overlay, topLeft = Offset(imageLeft, cTop), size = Size(cLeft - imageLeft, cBottom - cTop))
                            drawRect(overlay, topLeft = Offset(cRight, cTop), size = Size(imageLeft + w - cRight, cBottom - cTop))
                            drawRect(
                                color = androidx.compose.ui.graphics.Color.White,
                                topLeft = Offset(cLeft, cTop),
                                size = Size(cRight - cLeft, cBottom - cTop),
                                style = Stroke(width = 3f),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    CropSliderRow(stringResourceSafe(R.string.edit_crop_left), left, 0f, (right - 0.05f).coerceAtLeast(0.01f)) { left = it }
                    CropSliderRow(stringResourceSafe(R.string.edit_crop_top), top, 0f, (bottom - 0.05f).coerceAtLeast(0.01f)) { top = it }
                    CropSliderRow(stringResourceSafe(R.string.edit_crop_right), right, (left + 0.05f).coerceAtMost(0.99f), 1f) { right = it }
                    CropSliderRow(stringResourceSafe(R.string.edit_crop_bottom), bottom, (top + 0.05f).coerceAtMost(0.99f), 1f) { bottom = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    CropCoordinateMapper.displayToSource(
                        CropRect(left, top, right, bottom),
                        page.rotationDegrees,
                    ),
                )
            }) { Text(stringResourceSafe(R.string.edit_confirm)) }
        },
        dismissButton = {
            Row {
                if (page.cropRect != null) {
                    TextButton(onClick = onClear) { Text(stringResourceSafe(R.string.edit_cancel)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResourceSafe(R.string.edit_cancel)) }
            }
        },
    )
}

@Composable
private fun CropSliderRow(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, modifier = Modifier.size(20.dp), style = MaterialTheme.typography.labelSmall)
        androidx.compose.material3.Slider(
            value = value.coerceIn(from, to),
            onValueChange = onValueChange,
            valueRange = from..to,
            modifier = Modifier.weight(1f),
        )
    }
}
