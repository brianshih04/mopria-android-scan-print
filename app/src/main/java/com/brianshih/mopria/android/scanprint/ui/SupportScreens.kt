package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.PrintMethod
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePack
import com.brianshih.mopria.android.scanprint.domain.OcrLanguageModel
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePackState
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePackStatus
import com.brianshih.mopria.android.scanprint.domain.OcrLanguageRegion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HistoryScreen(uiState: MopriaUiState) {
    val isTablet = isTabletLayout()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .run { if (isTablet) this.widthIn(max = 720.dp) else this },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.history_summary, uiState.jobs.size, uiState.jobs.count { it.status == JobStatus.Completed }),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (uiState.jobs.isEmpty()) {
            item { EmptyHistoryCard() }
        } else {
            items(uiState.jobs, key = { it.id }) { job -> JobHistoryCard(job) }
        }
    }
}

@Composable
private fun JobHistoryCard(job: JobRecord) {
    val icon = when (job.kind) {
        JobKind.Scan -> Icons.Outlined.DocumentScanner
        JobKind.Print -> Icons.Outlined.Print
        JobKind.Export -> Icons.Outlined.FolderOpen
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${stringResource(job.kind.labelRes)} · ${formatTime(job.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                JobStatusPill(job.status)
            }
            Text(
                job.detail ?: job.targetLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (job.status == JobStatus.Running || job.status == JobStatus.Queued) {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun SettingsScreen(
    uiState: MopriaUiState,
    onModeChanged: (IntegrationMode) -> Unit,
    onPrintMethodChanged: (PrintMethod) -> Unit,
    onFindDevices: () -> Unit,
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onOcrLanguagePackSelected: (OcrLanguagePack, Boolean) -> Unit,
    onActiveOcrLanguageChanged: (OcrLanguagePack) -> Unit,
    onDownloadOcrLanguagePack: (OcrLanguagePack) -> Unit,
    onDownloadSelectedOcrLanguagePacks: () -> Unit,
) {
    val isTablet = isTabletLayout()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .run { if (isTablet) this.widthIn(max = 720.dp) else this },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text(stringResource(R.string.settings_connection_mode), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeOptionCard(
                    title = stringResource(R.string.mode_mock),
                    description = stringResource(R.string.mode_mock_description),
                    icon = Icons.Outlined.Science,
                    selected = uiState.integrationMode == IntegrationMode.Mock,
                    enabled = !uiState.isBusy,
                    onClick = { onModeChanged(IntegrationMode.Mock) },
                )
                ModeOptionCard(
                    title = stringResource(R.string.mode_real),
                    description = stringResource(R.string.mode_real_description),
                    icon = Icons.Outlined.Wifi,
                    selected = uiState.integrationMode == IntegrationMode.Real,
                    enabled = !uiState.isBusy,
                    onClick = { onModeChanged(IntegrationMode.Real) },
                )
            }
        }
        if (!uiState.mockMode) {
            item {
                Text(stringResource(R.string.print_method_title), style = MaterialTheme.typography.headlineSmall)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeOptionCard(
                        title = stringResource(R.string.print_method_system),
                        description = stringResource(R.string.print_method_system_description),
                        icon = Icons.Outlined.Print,
                        selected = uiState.printMethod == PrintMethod.System,
                        enabled = !uiState.isBusy,
                        onClick = { onPrintMethodChanged(PrintMethod.System) },
                    )
                    ModeOptionCard(
                        title = stringResource(R.string.print_method_ipp),
                        description = stringResource(R.string.print_method_ipp_description),
                        icon = Icons.Outlined.Wifi,
                        selected = uiState.printMethod == PrintMethod.Ipp,
                        enabled = !uiState.isBusy,
                        onClick = { onPrintMethodChanged(PrintMethod.Ipp) },
                    )
                }
            }
        }
        if (!uiState.mockMode) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.devices.isEmpty()) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (uiState.devices.isEmpty()) Icons.Outlined.CloudOff else Icons.Outlined.CheckCircle,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (uiState.devices.isEmpty()) stringResource(R.string.settings_devices_not_found)
                                    else stringResource(R.string.settings_devices_found, uiState.devices.size),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (uiState.devices.isEmpty()) stringResource(R.string.settings_same_wifi)
                                    else stringResource(R.string.settings_escl_connected),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = onFindDevices, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (uiState.isDiscovering) R.string.settings_searching else R.string.settings_search_devices))
                        }
                    }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.settings_operation))
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column {
                    SettingInfoRow(
                        icon = Icons.Outlined.DocumentScanner,
                        title = stringResource(R.string.settings_scan),
                        detail = stringResource(R.string.settings_scan_detail),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Print,
                        title = stringResource(R.string.settings_print),
                        detail = stringResource(R.string.settings_print_detail),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Description,
                        title = stringResource(R.string.settings_files),
                        detail = stringResource(R.string.settings_files_detail),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Security,
                        title = stringResource(R.string.settings_privacy),
                        detail = stringResource(R.string.settings_privacy_detail),
                    )
                }
            }
        }
        item {
            LanguageSelector(selectedLanguage, onLanguageChanged)
        }
        item {
            OcrLanguagePacksSection(
                packs = uiState.ocrLanguagePacks,
                enabled = !uiState.isBusy,
                onSelected = onOcrLanguagePackSelected,
                onActiveChanged = onActiveOcrLanguageChanged,
                onDownload = onDownloadOcrLanguagePack,
                onDownloadSelected = onDownloadSelectedOcrLanguagePacks,
            )
        }
    }
}

@Composable
private fun OcrLanguagePacksSection(
    packs: List<OcrLanguagePackState>,
    enabled: Boolean,
    onSelected: (OcrLanguagePack, Boolean) -> Unit,
    onActiveChanged: (OcrLanguagePack) -> Unit,
    onDownload: (OcrLanguagePack) -> Unit,
    onDownloadSelected: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ocr_language_packs_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.ocr_language_packs_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDownloadSelected,
            enabled = enabled && packs.any {
                it.selected &&
                    it.language.model != OcrLanguageModel.Unsupported &&
                    it.status != OcrLanguagePackStatus.Downloading
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.ocr_language_packs_download_selected))
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column {
                OcrLanguageRegion.entries.forEach { region ->
                    val regionPacks = packs.filter { it.language.region == region }
                    if (regionPacks.isNotEmpty()) {
                        Text(
                            stringResource(region.labelRes),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        regionPacks.forEachIndexed { index, pack ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            OcrLanguagePackRow(
                                pack = pack,
                                enabled = enabled && pack.language.model != OcrLanguageModel.Unsupported,
                                onSelected = onSelected,
                                onActiveChanged = onActiveChanged,
                                onDownload = onDownload,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrLanguagePackRow(
    pack: OcrLanguagePackState,
    enabled: Boolean,
    onSelected: (OcrLanguagePack, Boolean) -> Unit,
    onActiveChanged: (OcrLanguagePack) -> Unit,
    onDownload: (OcrLanguagePack) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = pack.selected,
                onCheckedChange = { checked -> onSelected(pack.language, checked) },
                enabled = enabled,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(pack.language.labelRes), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(pack.status.labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        pack.status == OcrLanguagePackStatus.Failed ||
                        pack.status == OcrLanguagePackStatus.Unsupported
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            RadioButton(
                selected = pack.active,
                onClick = { onActiveChanged(pack.language) },
                enabled = enabled && pack.selected,
            )
        }
        if (pack.status == OcrLanguagePackStatus.Downloading) {
            LinearProgressIndicator(
                progress = { pack.progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        } else if (
            pack.status != OcrLanguagePackStatus.Downloading &&
            pack.language.model != OcrLanguageModel.Unsupported
        ) {
            TextButton(
                onClick = { onDownload(pack.language) },
                enabled = enabled,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.ocr_language_pack_download))
            }
        }
    }
}

@Composable
private fun LanguageSelector(
    selectedLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_language_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(stringResource(selectedLanguage.labelRes))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppLanguage.userSelectable.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(stringResource(language.labelRes)) },
                        onClick = {
                            expanded = false
                            onLanguageChanged(language)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
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
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingInfoRow(icon: ImageVector, title: String, detail: String) {
    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
