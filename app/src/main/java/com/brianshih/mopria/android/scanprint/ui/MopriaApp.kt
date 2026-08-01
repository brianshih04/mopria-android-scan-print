package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.print.PrintManager
import com.brianshih.mopria.android.scanprint.domain.DeviceKind
import com.brianshih.mopria.android.scanprint.domain.IntegrationDevice
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab(val label: String, val icon: ImageVector) {
    Home("首頁", Icons.Filled.Home),
    Documents("文件", Icons.Outlined.Description),
    History("紀錄", Icons.Filled.History),
    Settings("設定", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MopriaApp(viewModel: MopriaViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phonePrintLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val title = if (uris.size == 1) "手機文件" else "手機文件（${uris.size} 個）"
            viewModel.recordSystemPrint(uris.size)
            printManager.print(title, UriPrintAdapter(context, uris, title), null)
        }
    }
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.Home.name) }
    val selectedTab = AppTab.valueOf(selectedTabName)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedTab == AppTab.Home) "Mopria Scan & Print" else selectedTab.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (selectedTab == AppTab.Home) {
                            Text(
                                text = "${uiState.integrationMode.label} · 掃描與列印工作台",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectedTab != AppTab.Home) {
                        IconButton(onClick = { selectedTabName = AppTab.Home.name }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "回到首頁")
                        }
                    }
                },
                actions = {
                    if (selectedTab != AppTab.Settings) {
                        IconButton(onClick = { selectedTabName = AppTab.Settings.name }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "開啟設定")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTabName = tab.name },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    uiState = uiState,
                    onScan = {
                        selectedTabName = AppTab.Documents.name
                        viewModel.scan()
                    },
                    onPrint = {
                        selectedTabName = AppTab.Documents.name
                        viewModel.print()
                    },
                    onFindDevices = viewModel::discoverDevices,
                    onViewAll = { selectedTabName = AppTab.History.name },
                )

                AppTab.Documents -> DocumentsScreen(
                    uiState = uiState,
                    onScan = viewModel::scan,
                    onCreateDemo = viewModel::createDemoDocument,
                    onExport = viewModel::export,
                    onSaveJpegs = viewModel::saveJpegs,
                    onPrint = viewModel::print,
                    onPhonePrint = {
                        phonePrintLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                    },
                    onSystemPrint = { document ->
                        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        printManager.print(document.name, SystemPrintAdapter(document), null)
                    },
                )

                AppTab.History -> HistoryScreen(uiState)
                AppTab.Settings -> SettingsScreen(uiState, viewModel::setIntegrationMode)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    uiState: MopriaUiState,
    onScan: () -> Unit,
    onPrint: () -> Unit,
    onFindDevices: () -> Unit,
    onViewAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("文件工作台", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (uiState.mockMode) "先用模擬裝置跑通流程，接上真實設備後沿用同一套工作狀態。"
                    else "搜尋區域網路上的真實設備，沿用同一套掃描與列印工作狀態。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { ReadinessCard(uiState) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("開始工作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.DocumentScanner,
                        title = "掃描文件",
                        description = if (uiState.mockMode) "使用模擬 eSCL 裝置" else "使用真實 eSCL 裝置",
                        onClick = onScan,
                    )
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Print,
                        title = "列印文件",
                        description = if (uiState.mockMode) "使用模擬 Print Framework" else "使用 Android Print Framework",
                        onClick = onPrint,
                    )
                }
            }
        }
        item { DeviceStatusCard(uiState, onFindDevices) }
        item { RecentJobsCard(uiState, onViewAll) }
    }
}

@Composable
private fun ReadinessCard(uiState: MopriaUiState) {
    val modeLabel = uiState.integrationMode.label
    val title = when {
        uiState.isDiscovering -> "正在搜尋${modeLabel}設備"
        uiState.isBusy -> "工作執行中"
        uiState.devices.isNotEmpty() -> "${modeLabel}設備已就緒"
        else -> "可開始${modeLabel}流程"
    }
    val detail = when {
        uiState.isDiscovering -> if (uiState.mockMode) "掃描區域網路的動作目前以 Mock provider 代替" else "正在使用 Android NSD 探索區域網路服務"
        uiState.devices.isNotEmpty() -> "已找到 ${uiState.scanners.size} 個 scanner、${uiState.printers.size} 個 printer"
        else -> if (uiState.mockMode) "不需要連接實體 scanner／printer 即可測試文件流程" else "請先搜尋同一個區域網路上的 scanner／printer"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun ActionCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(154.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(uiState: MopriaUiState, onFindDevices: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("裝置狀態", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (uiState.devices.isEmpty()) "尚未搜尋；目前使用${uiState.integrationMode.label}"
                        else if (uiState.mockMode) "模擬裝置可供流程測試" else "已找到區域網路上的真實設備",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = onFindDevices,
                    label = { Text(if (uiState.isDiscovering) "搜尋中" else "搜尋") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            DeviceStatusRow(
                device = uiState.scanners.firstOrNull(),
                kind = DeviceKind.Scanner,
                fallback = "eSCL / AirScan",
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            DeviceStatusRow(
                device = uiState.printers.firstOrNull(),
                kind = DeviceKind.Printer,
                fallback = "Android Print Framework",
            )
        }
    }
}

@Composable
private fun DeviceStatusRow(device: IntegrationDevice?, kind: DeviceKind, fallback: String) {
    val icon = if (kind == DeviceKind.Scanner) Icons.Outlined.DocumentScanner else Icons.Outlined.Print
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(kind.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(device?.name ?: fallback, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = if (device == null) "未搜尋" else if (device.isMock) "Mock 就緒" else "已就緒",
            style = MaterialTheme.typography.labelMedium,
            color = if (device == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RecentJobsCard(uiState: MopriaUiState, onViewAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近工作", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onViewAll, enabled = uiState.jobs.isNotEmpty()) { Text("查看全部") }
            }
            if (uiState.jobs.isEmpty()) {
                EmptyState(Icons.Outlined.FolderOpen, "還沒有工作紀錄", "完成第一次模擬掃描或列印後，紀錄會顯示在這裡。")
            } else {
                uiState.jobs.take(2).forEach { job -> JobRow(job) }
            }
        }
    }
}

@Composable
private fun DocumentsScreen(
    uiState: MopriaUiState,
    onScan: () -> Unit,
    onCreateDemo: () -> Unit,
    onExport: (String) -> Unit,
    onSaveJpegs: (String) -> Unit,
    onPrint: (String) -> Unit,
    onPhonePrint: () -> Unit,
    onSystemPrint: (MopriaDocument) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("文件庫", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (uiState.mockMode) "先用 mock 文件完成預覽、匯出與列印流程。"
                            else "使用真實設備取得文件；目前 eSCL 工作執行仍在開發中。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AssistChip(onClick = onScan, label = { Text("開始掃描") }, leadingIcon = { Icon(Icons.Outlined.DocumentScanner, null) })
                }
            }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("從手機資料夾列印", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("選取 PDF 或 JPEG，交給 Android 系統列印預覽。", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onPhonePrint) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("選擇檔案")
                    }
                }
            }
        }
        if (uiState.isBusy) {
            item { OperationProgressCard(uiState) }
        }
        if (uiState.documents.isEmpty()) {
            item {
                EmptyScreen(
                    icon = Icons.Outlined.Description,
                    title = "文件庫是空的",
                    description = "建立一份示範文件，或執行模擬掃描，接著測試真正的文件工作流。",
                    actionLabel = "建立示範文件",
                    actionIcon = Icons.Outlined.FileOpen,
                    onAction = onCreateDemo,
                )
            }
        } else {
            items(uiState.documents, key = { it.id }) { document ->
                DocumentCard(document, onExport, onSaveJpegs, onPrint, onSystemPrint)
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
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(activeJob?.title ?: "搜尋${uiState.integrationMode.label}設備", fontWeight = FontWeight.SemiBold)
            Text(
                activeJob?.detail ?: if (uiState.mockMode) "Mock provider 正在回應" else "Android NSD 正在探索區域網路",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${activeJob?.progress ?: 15}%", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DocumentCard(
    document: MopriaDocument,
    onExport: (String) -> Unit,
    onSaveJpegs: (String) -> Unit,
    onPrint: (String) -> Unit,
    onSystemPrint: (MopriaDocument) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${document.pages.size} 頁 · ${document.sourceLabel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("頁面預覽", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            document.pages.forEach { page ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${page.pageNumber}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(page.title, modifier = Modifier.weight(1f))
                    Text("A4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (document.exportedPath != null) {
                Text("已儲存檔案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                document.savedFiles.forEach { savedFile ->
                    Text(
                        "${savedFile.format.label} · ${savedFile.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onExport(document.id) }, modifier = Modifier.weight(1f)) {
                    Text("儲存 PDF")
                }
                OutlinedButton(onClick = { onSaveJpegs(document.id) }, modifier = Modifier.weight(1f)) {
                    Text("儲存 JPEG")
                }
            }
            Button(onClick = { onPrint(document.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Print, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("模擬列印")
            }
            TextButton(onClick = { onSystemPrint(document) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Print, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("開啟 Android 系統列印預覽")
            }
        }
    }
}

@Composable
private fun HistoryScreen(uiState: MopriaUiState) {
    if (uiState.jobs.isEmpty()) {
        EmptyScreen(
            icon = Icons.Outlined.History,
            title = "工作紀錄是空的",
            description = "模擬掃描、匯出或列印後，這裡會顯示每個工作的狀態與目的地。",
            actionLabel = null,
            actionIcon = null,
            onAction = {},
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("工作紀錄", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        items(uiState.jobs, key = { it.id }) { job ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(job.kind.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(job.status.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(formatTime(job.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(job.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(job.targetLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (job.detail != null) Text(job.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(uiState: MopriaUiState, onModeChanged: (IntegrationMode) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("切換整合模式來選擇內建模擬 provider 或區域網路真實設備探索。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("整合模式", fontWeight = FontWeight.SemiBold)
                        Text(uiState.integrationMode.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IntegrationModeButton(
                        mode = IntegrationMode.Mock,
                        selected = uiState.integrationMode == IntegrationMode.Mock,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f),
                        onClick = { onModeChanged(IntegrationMode.Mock) },
                    )
                    IntegrationModeButton(
                        mode = IntegrationMode.Real,
                        selected = uiState.integrationMode == IntegrationMode.Real,
                        enabled = !uiState.isBusy,
                        modifier = Modifier.weight(1f),
                        onClick = { onModeChanged(IntegrationMode.Real) },
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.large,
            ) {
                Column {
                    SettingRow(
                        Icons.Outlined.Wifi,
                        "裝置探索",
                        if (uiState.mockMode) "Mock provider 提供固定測試裝置" else "Real provider 使用 Android NsdManager 探索 eSCL／IPP",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
                    SettingRow(
                        Icons.Outlined.Print,
                        "列印服務",
                        if (uiState.mockMode) "Mock provider 模擬列印工作" else "Android Print Framework 系統預覽仍可使用",
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 18.dp))
                    SettingRow(Icons.Outlined.Info, "掃描輸出", "PDF／JPEG 會寫入 Download/Mopria Scan & Print/Scans")
                }
            }
        }
    }
}

@Composable
private fun IntegrationModeButton(
    mode: IntegrationMode,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(mode.label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(mode.label)
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, detail: String) {
    Row(modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun JobRow(job: JobRecord) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (job.kind) {
                JobKind.Scan -> Icons.Outlined.DocumentScanner
                JobKind.Print -> Icons.Outlined.Print
                JobKind.Export -> Icons.Outlined.FolderOpen
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(job.detail ?: job.targetLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(job.status.label, style = MaterialTheme.typography.labelMedium, color = if (job.status == JobStatus.Completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyScreen(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String?,
    actionIcon: ImageVector?,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(modifier = Modifier.size(72.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null && actionIcon != null) {
                Button(onClick = onAction) {
                    Icon(actionIcon, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(actionLabel)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
