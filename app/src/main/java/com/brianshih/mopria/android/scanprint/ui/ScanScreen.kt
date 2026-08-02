package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.ScanColorMode
import com.brianshih.mopria.android.scanprint.domain.ScanInputSource
import com.brianshih.mopria.android.scanprint.domain.ScanSettings
import kotlin.math.roundToInt

@Composable
internal fun ScanScreen(
    uiState: MopriaUiState,
    onScan: () -> Unit,
    onScanSettingsChanged: (ScanSettings) -> Unit,
    onContinueFlatbed: () -> Unit,
    onFinishFlatbed: () -> Unit,
) {
    if (uiState.awaitingNextFlatbedPage) {
        val pageCount = uiState.pendingFlatbedDocumentId
            ?.let { id -> uiState.documents.firstOrNull { it.id == id } }
            ?.pages
            ?.size
            ?: 1
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
            title = { Text("第 $pageCount 頁完成") },
            text = { Text("換上下一頁繼續掃描，或完成並儲存為單一 PDF。") },
            confirmButton = { TextButton(onClick = onContinueFlatbed) { Text("下一頁") } },
            dismissButton = { TextButton(onClick = onFinishFlatbed) { Text("完成 PDF") } },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Text("掃描文件", style = MaterialTheme.typography.headlineSmall) }
        item {
            ScanComposerCard(
                settings = uiState.scanSettings,
                enabled = !uiState.isBusy,
                onChanged = onScanSettingsChanged,
                onScan = onScan,
            )
        }
        if (uiState.isBusy) item { OperationProgressCard(uiState) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanComposerCard(
    settings: ScanSettings,
    enabled: Boolean,
    onChanged: (ScanSettings) -> Unit,
    onScan: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("掃描設定", style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            append("${settings.inputSource.shortLabel} · ${settings.resolutionDpi} dpi · ${settings.colorMode.label}")
                            if (settings.inputSource == ScanInputSource.Adf) append(" · 最多 ${settings.maxPages} 頁")
                            if (settings.combineAsPdf) append(" · PDF")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件來源", style = MaterialTheme.typography.labelLarge)
                ScanInputSource.entries.forEach { source ->
                    SourceOption(
                        source = source,
                        selected = source == settings.inputSource,
                        enabled = enabled,
                        maxPages = settings.maxPages,
                        onClick = { onChanged(settings.copy(inputSource = source)) },
                    )
                }
            }

            FilterChip(
                selected = settings.combineAsPdf,
                onClick = { onChanged(settings.copy(combineAsPdf = !settings.combineAsPdf)) },
                enabled = enabled,
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                label = {
                    Text(if (settings.inputSource == ScanInputSource.Flatbed) "逐頁合併 PDF" else "合併為多頁 PDF")
                },
            )

            if (settings.inputSource == ScanInputSource.Adf) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("頁數上限", style = MaterialTheme.typography.labelLarge)
                        Text("${settings.maxPages} 頁", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = settings.maxPages.toFloat(),
                        onValueChange = { onChanged(settings.copy(maxPages = it.roundToInt().coerceIn(1, 50))) },
                        valueRange = 1f..50f,
                        steps = 48,
                        enabled = enabled,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("解析度", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(150, 300, 600).forEach { dpi ->
                        FilterChip(
                            selected = settings.resolutionDpi == dpi,
                            onClick = { onChanged(settings.copy(resolutionDpi = dpi)) },
                            enabled = enabled,
                            label = { Text("$dpi dpi") },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("色彩", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanColorMode.entries.forEach { colorMode ->
                        FilterChip(
                            selected = settings.colorMode == colorMode,
                            onClick = { onChanged(settings.copy(colorMode = colorMode)) },
                            enabled = enabled,
                            label = { Text(colorMode.label) },
                        )
                    }
                }
            }

            Button(
                onClick = onScan,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("開始掃描")
            }
        }
    }
}

@Composable
private fun SourceOption(
    source: ScanInputSource,
    selected: Boolean,
    enabled: Boolean,
    maxPages: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(source.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (source == ScanInputSource.Flatbed) "單頁" else "多頁 · 最多 $maxPages 頁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OperationProgressCard(uiState: MopriaUiState) {
    val activeJob = uiState.jobs.firstOrNull { it.id == uiState.activeJobId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(activeJob?.title ?: "正在搜尋裝置", style = MaterialTheme.typography.titleMedium)
            Text(
                activeJob?.detail ?: "搜尋中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            LinearProgressIndicator(
                progress = { (activeJob?.progress ?: 15) / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
