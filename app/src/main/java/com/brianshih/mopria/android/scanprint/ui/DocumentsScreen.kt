package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun DocumentsScreen(
    uiState: MopriaUiState,
    onExport: (String) -> Unit,
    onSaveJpegs: (String) -> Unit,
    onPrint: (String) -> Unit,
    onShare: (String) -> Unit,
    onSystemPreview: (MopriaDocument) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onEditDocument: (String) -> Unit,
) {
    var previewPage by remember { mutableStateOf<DocumentPage?>(null) }
    val isTablet = isTabletLayout()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .run { if (isTablet) this.widthIn(max = 720.dp) else this },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.documents_my_files), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.documents_count, uiState.documents.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (uiState.documents.isEmpty()) {
            item { EmptyDocumentState() }
        } else {
            items(uiState.documents, key = { it.id }) { document ->
                DocumentCard(
                    document = document,
                    integrationMode = uiState.integrationMode,
                    enabled = !uiState.isBusy,
                    onExport = onExport,
                    onSaveJpegs = onSaveJpegs,
                    onPrint = onPrint,
                    onShare = onShare,
                    onSystemPreview = onSystemPreview,
                    onPreviewPage = { previewPage = it },
                    onDeleteDocument = onDeleteDocument,
                    onEditDocument = onEditDocument,
                )
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }

    previewPage?.let { page ->
        PagePreviewDialog(page = page, onDismiss = { previewPage = null })
    }
}

@Composable
private fun DocumentCard(
    document: MopriaDocument,
    integrationMode: IntegrationMode,
    enabled: Boolean,
    onExport: (String) -> Unit,
    onSaveJpegs: (String) -> Unit,
    onPrint: (String) -> Unit,
    onShare: (String) -> Unit,
    onSystemPreview: (MopriaDocument) -> Unit,
    onPreviewPage: (DocumentPage) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onEditDocument: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        document.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.documents_page_source, document.pages.size, document.sourceLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(document.pages, key = { it.id }) { page ->
                    PageThumbnail(page = page, onClick = { onPreviewPage(page) })
                }
            }

            if (document.savedFiles.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.documents_saved_files, document.savedFiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onPrint(document.id) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Print, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.documents_print))
                }
                OutlinedButton(
                    onClick = { onShare(document.id) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.documents_share))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onExport(document.id) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.format_pdf))
                }
                OutlinedButton(
                    onClick = { onSaveJpegs(document.id) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.format_jpeg))
                }
            }
            if (integrationMode == IntegrationMode.Mock) {
                TextButton(
                    onClick = { onSystemPreview(document) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.documents_system_preview))
                }
            }
            TextButton(
                onClick = { onEditDocument(document.id) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.edit_title))
            }
            TextButton(
                onClick = { onDeleteDocument(document.id) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.documents_delete))
            }
        }
    }
}

@Composable
private fun PageThumbnail(page: DocumentPage, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(126.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 1.dp,
        ) {
            PagePreview(page = page, requestedWidth = 420, requestedHeight = 568)
        }
        Text(
            stringResource(R.string.documents_page_label, page.pageNumber, page.title),
            modifier = Modifier.padding(horizontal = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PagePreview(
    page: DocumentPage,
    requestedWidth: Int,
    requestedHeight: Int,
) {
    val context = LocalContext.current
    val hasSource = page.imagePath != null || page.pdfPath != null
    val result by produceState<Result<android.graphics.Bitmap?>?>(
        initialValue = null,
        key1 = page.id,
        key2 = requestedWidth,
        key3 = requestedHeight,
    ) {
        value = runCatching {
            DocumentPreviewLoader.load(context, page, requestedWidth, requestedHeight)
        }
    }
    val bitmap = result?.getOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.documents_scan_result, page.pageNumber),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            hasSource && result == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> MockPagePreview(page)
        }
    }
}

@Composable
private fun MockPagePreview(page: DocumentPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.58f)
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.extraSmall,
        ) {}
        Text(
            page.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        repeat(5) { index ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (index == 4) 0.66f else 1f)
                    .height(5.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.extraSmall,
            ) {}
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.documents_mock_page, page.pageNumber),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PagePreviewDialog(page: DocumentPage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.documents_page_label, page.pageNumber, page.title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.documents_close_preview))
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.74f),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    PagePreview(page = page, requestedWidth = 1200, requestedHeight = 1620)
                }
            }
        }
    }
}

@Composable
private fun EmptyDocumentState() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Text(stringResource(R.string.documents_empty), style = MaterialTheme.typography.titleLarge)
        }
    }
}
