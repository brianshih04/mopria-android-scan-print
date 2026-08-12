package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Photo
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.OcrMode
import com.brianshih.mopria.android.scanprint.domain.ScanAdfMode
import com.brianshih.mopria.android.scanprint.domain.ScanColorMode
import com.brianshih.mopria.android.scanprint.domain.ScanDocumentSize
import com.brianshih.mopria.android.scanprint.domain.ScanPreset
import com.brianshih.mopria.android.scanprint.domain.ScanInputSource
import com.brianshih.mopria.android.scanprint.domain.ScanSettings
import com.brianshih.mopria.android.scanprint.domain.ScannerCapabilities
import com.brianshih.mopria.android.scanprint.domain.EnhancementStrength
import kotlin.math.roundToInt

@Composable
internal fun ScanScreen(
    uiState: MopriaUiState,
    onScan: () -> Unit,
    onScanSettingsChanged: (ScanSettings) -> Unit,
    onPresetChange: (ScanPreset) -> Unit,
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
            title = { Text(stringResource(R.string.scan_page_complete_title, pageCount)) },
            text = { Text(stringResource(R.string.scan_page_complete_message)) },
            confirmButton = { TextButton(onClick = onContinueFlatbed) { Text(stringResource(R.string.scan_next_page)) } },
            dismissButton = { TextButton(onClick = onFinishFlatbed) { Text(stringResource(R.string.scan_finish_pdf)) } },
        )
    }
    val isTablet = isTabletLayout()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .run { if (isTablet) this.widthIn(max = 720.dp) else this },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            PresetSelectorCard(
                selectedPreset = uiState.scanPreset,
                enabled = !uiState.isBusy,
                onSelect = onPresetChange,
            )
        }
        item {
            ScanComposerCard(
                settings = uiState.scanSettings,
                caps = uiState.scannerCapabilities,
                enabled = !uiState.isBusy,
                onChanged = onScanSettingsChanged,
                onScan = onScan,
            )
        }
        if (uiState.isBusy) item { OperationProgressCard(uiState) }
    }
}


@Composable
private fun PresetSelectorCard(
    selectedPreset: ScanPreset,
    enabled: Boolean,
    onSelect: (ScanPreset) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.preset_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScanPreset.entries.forEach { preset ->
                PresetOption(
                    preset = preset,
                    selected = preset == selectedPreset,
                    enabled = enabled,
                    onClick = { onSelect(preset) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PresetOption(
    preset: ScanPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (preset) {
                    ScanPreset.Document -> Icons.Outlined.Description
                    ScanPreset.Photo -> Icons.Outlined.Photo
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else contentColor,
            )
            Text(
                stringResource(preset.labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
            Text(
                stringResource(preset.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanComposerCard(
    settings: ScanSettings,
    caps: ScannerCapabilities,
    enabled: Boolean,
    onChanged: (ScanSettings) -> Unit,
    onScan: () -> Unit,
) {
    var summary = stringResource(
        R.string.scan_summary_format,
        stringResource(settings.inputSource.shortLabelRes),
        settings.resolutionDpi,
        stringResource(settings.colorMode.labelRes),
    )
    summary = stringResource(
        R.string.scan_summary_size,
        summary,
        stringResource(settings.documentSize.labelRes),
    )
    if (settings.inputSource == ScanInputSource.Adf) {
        summary = stringResource(
            R.string.scan_summary_adf_mode,
            summary,
            stringResource(settings.adfMode.labelRes),
        )
        summary = stringResource(R.string.scan_summary_adf_pages, summary, settings.maxPages)
    }
    if (settings.combineAsPdf) summary = stringResource(R.string.scan_summary_pdf, summary)
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
                    Text(stringResource(R.string.scan_settings), style = MaterialTheme.typography.titleMedium)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_source), style = MaterialTheme.typography.labelLarge)
                ScanInputSource.entries.filter { it in caps.supportedSources }.forEach { source ->
                    SourceOption(
                        source = source,
                        selected = source == settings.inputSource,
                        enabled = enabled,
                        maxPages = settings.maxPages,
                        onClick = { onChanged(settings.copy(inputSource = source)) },
                    )
                }
            }

            if (settings.inputSource == ScanInputSource.Adf) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.scan_adf_sides), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScanAdfMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.adfMode == mode,
                                onClick = { onChanged(settings.copy(adfMode = mode)) },
                                enabled = enabled && mode in caps.supportedAdfModes,
                                label = { Text(stringResource(mode.labelRes)) },
                            )
                        }
                    }
                    if (ScanAdfMode.Duplex !in caps.supportedAdfModes) {
                        Text(
                            stringResource(R.string.scan_adf_duplex_unsupported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_size), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.scan_size_documents),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ScanDocumentSize.Auto,
                        ScanDocumentSize.A4,
                        ScanDocumentSize.Letter,
                        ScanDocumentSize.A5,
                    ).forEach { size ->
                        FilterChip(
                            selected = settings.documentSize == size,
                            onClick = { onChanged(settings.copy(documentSize = size)) },
                            enabled = enabled,
                            label = { Text(stringResource(size.labelRes)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.scan_size_photos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ScanDocumentSize.Photo4x6,
                        ScanDocumentSize.Photo5x7,
                        ScanDocumentSize.Photo8x10,
                    ).forEach { size ->
                        FilterChip(
                            selected = settings.documentSize == size,
                            onClick = { onChanged(settings.copy(documentSize = size)) },
                            enabled = enabled,
                            label = { Text(stringResource(size.labelRes)) },
                        )
                    }
                }
            }

            // Background enhancement: strength selector (Light / Normal / Strong)
            if (settings.enhanceBackground != null) {
                Text(
                    stringResource(R.string.scan_enhance_background),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnhancementStrength.entries.forEach { strength ->
                        FilterChip(
                            selected = settings.enhanceBackground == strength,
                            onClick = { onChanged(settings.copy(enhanceBackground = strength)) },
                            enabled = enabled,
                            label = { Text(stringResource(strength.labelRes)) },
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.scan_processing),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.ocrMode != OcrMode.Disabled,
                    onClick = {
                        onChanged(
                            settings.copy(
                                ocrMode = if (settings.ocrMode == OcrMode.Disabled) OcrMode.MlKit else OcrMode.Disabled,
                            ),
                        )
                    },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.scan_ocr)) },
                )
                FilterChip(
                    selected = settings.deskew,
                    onClick = { onChanged(settings.copy(deskew = !settings.deskew)) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.scan_deskew)) },
                )
                FilterChip(
                    selected = settings.autoCrop,
                    onClick = { onChanged(settings.copy(autoCrop = !settings.autoCrop)) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.scan_auto_crop)) },
                )
                if (settings.inputSource == ScanInputSource.Adf) {
                    FilterChip(
                        selected = settings.dropBlankPages,
                        onClick = { onChanged(settings.copy(dropBlankPages = !settings.dropBlankPages)) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.scan_drop_blank_pages)) },
                    )
                }
            }
            if (settings.ocrMode != OcrMode.Disabled) {
                Text(
                    stringResource(settings.ocrMode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilterChip(
                selected = settings.combineAsPdf,
                onClick = { onChanged(settings.copy(combineAsPdf = !settings.combineAsPdf)) },
                enabled = enabled,
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                label = {
                    Text(
                        stringResource(
                            if (settings.inputSource == ScanInputSource.Flatbed) R.string.scan_combine_flatbed else R.string.scan_combine_adf,
                        ),
                    )
                },
            )

            FilterChip(
                selected = settings.searchablePdf,
                onClick = { onChanged(settings.copy(searchablePdf = !settings.searchablePdf)) },
                enabled = enabled && settings.ocrMode != OcrMode.Disabled,
                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                label = { Text(stringResource(R.string.scan_searchable_pdf)) },
            )
            if (settings.ocrMode == OcrMode.Disabled) {
                Text(
                    stringResource(R.string.scan_searchable_pdf_requires_ocr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (settings.inputSource == ScanInputSource.Adf) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.scan_page_limit), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.scan_page_count, settings.maxPages), color = MaterialTheme.colorScheme.primary)
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
                Text(stringResource(R.string.scan_resolution), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    caps.supportedResolutions.sorted().forEach { dpi ->
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
                Text(stringResource(R.string.scan_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanColorMode.entries.filter { it in caps.supportedColorModes }.forEach { colorMode ->
                        FilterChip(
                            selected = settings.colorMode == colorMode,
                            onClick = { onChanged(settings.copy(colorMode = colorMode)) },
                            enabled = enabled,
                            label = { Text(stringResource(colorMode.labelRes)) },
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
                Text(stringResource(R.string.scan_start))
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
                Text(stringResource(source.labelRes), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (source == ScanInputSource.Flatbed) stringResource(R.string.scan_single_page)
                    else stringResource(R.string.scan_multiple_pages_max, maxPages),
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
            Text(activeJob?.title ?: stringResource(R.string.scan_searching_devices), style = MaterialTheme.typography.titleMedium)
            Text(
                activeJob?.detail ?: stringResource(R.string.settings_searching),
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
