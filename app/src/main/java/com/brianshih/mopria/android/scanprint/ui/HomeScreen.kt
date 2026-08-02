package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.ScanInputSource

@Composable
internal fun HomeScreen(
    uiState: MopriaUiState,
    onOpenScan: () -> Unit,
    onPhonePrint: () -> Unit,
    onFindDevices: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DashboardHeader(uiState = uiState, onOpenSettings = onOpenSettings)
        }
        item {
            ScanHeroCard(uiState = uiState, onClick = onOpenScan)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PrintWidget(
                    modifier = Modifier.weight(1f),
                    onClick = onPhonePrint,
                )
                DeviceWidget(
                    modifier = Modifier.weight(1f),
                    uiState = uiState,
                    onClick = onFindDevices,
                )
            }
        }
        item {
            RecentJobsWidget(uiState = uiState, onOpenHistory = onOpenHistory)
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun DashboardHeader(uiState: MopriaUiState, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (uiState.mockMode) Icons.Outlined.Science else Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    uiState.isBusy -> stringResource(R.string.home_busy)
                    uiState.mockMode -> stringResource(R.string.home_test_mode)
                    else -> stringResource(R.string.home_physical_devices)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.connection_mode))
        }
    }
}

@Composable
private fun ScanHeroCard(uiState: MopriaUiState, onClick: () -> Unit) {
    val activeJob = uiState.jobs.firstOrNull { it.id == uiState.activeJobId }
    val activeDetail = activeJob?.detail
    val scanSummary = if (activeDetail != null) {
        activeDetail
    } else {
        var summary = stringResource(
            R.string.scan_summary_format,
            stringResource(uiState.scanSettings.inputSource.shortLabelRes),
            uiState.scanSettings.resolutionDpi,
            stringResource(uiState.scanSettings.colorMode.labelRes),
        )
        if (uiState.scanSettings.inputSource == ScanInputSource.Adf) {
            summary = stringResource(R.string.scan_summary_adf_pages, summary, uiState.scanSettings.maxPages)
        }
        if (uiState.scanSettings.combineAsPdf) summary = stringResource(R.string.scan_summary_pdf, summary)
        summary
    }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2447A5), Color(0xFF00756E)),
                    ),
                )
                .padding(22.dp),
        ) {
            Column(modifier = Modifier.matchParentSize()) {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = MaterialTheme.shapes.large,
                    color = Color.White.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.DocumentScanner,
                            contentDescription = null,
                            modifier = Modifier.size(29.dp),
                            tint = Color.White,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.home_scan_document), style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    scanSummary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PrintWidget(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Print,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.home_print), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_print_formats), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DeviceWidget(
    uiState: MopriaUiState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scannerReady = uiState.mockMode || uiState.scanners.isNotEmpty()
    Card(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceGauge(
                    icon = Icons.Outlined.DocumentScanner,
                    ready = scannerReady,
                    description = stringResource(if (scannerReady) R.string.home_scanner_ready else R.string.home_scanner_missing),
                )
                DeviceGauge(
                    icon = Icons.Outlined.Print,
                    ready = true,
                    description = stringResource(R.string.home_system_print_ready),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.home_devices), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (uiState.isDiscovering) stringResource(R.string.home_searching)
                    else if (scannerReady) stringResource(R.string.home_ready)
                    else stringResource(R.string.home_search),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceGauge(icon: ImageVector, ready: Boolean, description: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) {},
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { if (ready) 1f else 0.18f },
            modifier = Modifier.matchParentSize(),
            color = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeWidth = 5.dp,
        )
        Icon(icon, contentDescription = description, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun RecentJobsWidget(uiState: MopriaUiState, onOpenHistory: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.home_recent), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onOpenHistory, enabled = uiState.jobs.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = stringResource(R.string.home_job_history))
                }
            }
            if (uiState.jobs.isEmpty()) {
                Text(stringResource(R.string.home_no_history), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                uiState.jobs.take(2).forEachIndexed { index, job ->
                    if (index > 0) HorizontalDivider()
                    HomeJobRow(job)
                }
            }
        }
    }
}

@Composable
private fun HomeJobRow(job: JobRecord) {
    val icon = when (job.kind) {
        JobKind.Scan -> Icons.Outlined.DocumentScanner
        JobKind.Print -> Icons.Outlined.Print
        JobKind.Export -> Icons.Outlined.FolderOpen
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                job.detail ?: job.targetLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        JobStatusPill(job.status)
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun JobStatusPill(status: JobStatus) {
    val (container, content) = when (status) {
        JobStatus.Completed -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        JobStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        JobStatus.Cancelled -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        JobStatus.Queued, JobStatus.Running -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(status.labelRes),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
