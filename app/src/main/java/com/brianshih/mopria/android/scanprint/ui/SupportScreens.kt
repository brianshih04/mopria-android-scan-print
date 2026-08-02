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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun HistoryScreen(uiState: MopriaUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("工作紀錄", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${uiState.jobs.size} 筆 · ${uiState.jobs.count { it.status == JobStatus.Completed }} 完成",
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
                        "${job.kind.label} · ${formatTime(job.createdAt)}",
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
            Text("還沒有工作紀錄", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun SettingsScreen(
    uiState: MopriaUiState,
    onModeChanged: (IntegrationMode) -> Unit,
    onFindDevices: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Text("連線模式", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeOptionCard(
                    title = "測試模式",
                    description = "內建裝置 · 離線",
                    icon = Icons.Outlined.Science,
                    selected = uiState.integrationMode == IntegrationMode.Mock,
                    enabled = !uiState.isBusy,
                    onClick = { onModeChanged(IntegrationMode.Mock) },
                )
                ModeOptionCard(
                    title = "實體裝置",
                    description = "eSCL 掃描 · Android 列印",
                    icon = Icons.Outlined.Wifi,
                    selected = uiState.integrationMode == IntegrationMode.Real,
                    enabled = !uiState.isBusy,
                    onClick = { onModeChanged(IntegrationMode.Real) },
                )
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
                                    if (uiState.devices.isEmpty()) "尚未找到裝置" else "找到 ${uiState.devices.size} 個裝置",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (uiState.devices.isEmpty()) "同一個 Wi‑Fi" else "eSCL 已連線",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(onClick = onFindDevices, enabled = !uiState.isBusy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (uiState.isDiscovering) "搜尋中" else "搜尋裝置")
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("運作方式")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column {
                    SettingInfoRow(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "掃描",
                        detail = "eSCL · Flatbed / ADF",
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Print,
                        title = "列印",
                        detail = "Android 系統列印",
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Description,
                        title = "檔案",
                        detail = "Download/Mopria Scan & Print/Scans",
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp))
                    SettingInfoRow(
                        icon = Icons.Outlined.Security,
                        title = "隱私",
                        detail = "本機 · 不備份",
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
