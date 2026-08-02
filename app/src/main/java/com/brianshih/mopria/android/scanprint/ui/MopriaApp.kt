package com.brianshih.mopria.android.scanprint.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode

internal enum class AppDestination(val label: String, val icon: ImageVector) {
    Home("首頁", Icons.Filled.Home),
    Scan("掃描", Icons.Outlined.DocumentScanner),
    Print("列印", Icons.Outlined.Print),
    Documents("文件", Icons.Filled.Description),
    History("紀錄", Icons.Filled.History),
    Settings("設定", Icons.Filled.Settings),
}

private enum class PendingExportKind { Pdf, Jpeg }

private data class PendingExport(val documentId: String, val kind: PendingExportKind)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MopriaApp(viewModel: MopriaViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedDestinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    val selectedDestination = AppDestination.entries
        .firstOrNull { it.name == selectedDestinationName }
        ?: AppDestination.Home

    BackHandler(enabled = selectedDestination != AppDestination.Home) {
        selectedDestinationName = AppDestination.Home.name
    }

    fun openPrint(title: String, adapter: PrintDocumentAdapter) {
        runCatching {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(title, adapter, null)
        }.onSuccess { printJob ->
            viewModel.monitorSystemPrint(printJob, title)
        }.onFailure { error ->
            viewModel.reportMessage("無法開啟系統列印：${error.message ?: "請確認列印服務已啟用"}")
        }
    }

    val phonePrintLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val title = if (uris.size == 1) "手機文件" else "手機文件（${uris.size} 個）"
            selectedDestinationName = AppDestination.Home.name
            openPrint(title, UriPrintAdapter(context, uris, title))
        }
    }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingExport
        pendingExport = null
        if (granted && request != null) {
            when (request.kind) {
                PendingExportKind.Pdf -> viewModel.export(request.documentId)
                PendingExportKind.Jpeg -> viewModel.saveJpegs(request.documentId)
            }
        } else if (!granted) {
            viewModel.reportMessage("Android 9 需要儲存權限才能寫入 Download 資料夾")
        }
    }

    fun exportDocument(documentId: String, kind: PendingExportKind) {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = PendingExport(documentId, kind)
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            when (kind) {
                PendingExportKind.Pdf -> viewModel.export(documentId)
                PendingExportKind.Jpeg -> viewModel.saveJpegs(documentId)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.printRequests.collect { document ->
            selectedDestinationName = AppDestination.Home.name
            openPrint(document.name, SystemPrintAdapter(document, context))
        }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.shareRequests.collect { request ->
            val uri = Uri.parse(request.uri)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(request.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(sendIntent, "分享 ${request.title}"))
            }.onFailure { error ->
                viewModel.reportMessage("無法開啟分享選單：${error.message ?: "沒有可用的 App"}")
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectedDestination != AppDestination.Home) {
                TopAppBar(
                    title = { Text(selectedDestination.label, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { selectedDestinationName = AppDestination.Home.name }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "回到首頁")
                        }
                    },
                    actions = {
                        IconButton(onClick = { selectedDestinationName = AppDestination.Settings.name }) {
                            Icon(
                                imageVector = if (uiState.integrationMode == IntegrationMode.Mock) {
                                    Icons.Outlined.Science
                                } else {
                                    Icons.Outlined.Wifi
                                },
                                contentDescription = "連線模式",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries
                    .filter { it != AppDestination.Scan && it != AppDestination.Print }
                    .forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selectedDestination,
                        onClick = { selectedDestinationName = destination.name },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
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
                .padding(innerPadding),
        ) {
            when (selectedDestination) {
                AppDestination.Home -> HomeScreen(
                    uiState = uiState,
                    onOpenScan = { selectedDestinationName = AppDestination.Scan.name },
                    onPhonePrint = { selectedDestinationName = AppDestination.Print.name },
                    onFindDevices = viewModel::discoverDevices,
                    onOpenHistory = { selectedDestinationName = AppDestination.History.name },
                    onOpenSettings = { selectedDestinationName = AppDestination.Settings.name },
                )

                AppDestination.Scan -> ScanScreen(
                    uiState = uiState,
                    onScan = viewModel::scan,
                    onScanSettingsChanged = viewModel::updateScanSettings,
                    onContinueFlatbed = viewModel::continueFlatbedScan,
                    onFinishFlatbed = viewModel::finishFlatbedScan,
                )

                AppDestination.Print -> PrintScreen(
                    onChooseFiles = {
                        phonePrintLauncher.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                    },
                    onOpenDocuments = { selectedDestinationName = AppDestination.Documents.name },
                )

                AppDestination.Documents -> DocumentsScreen(
                    uiState = uiState,
                    onExport = { documentId -> exportDocument(documentId, PendingExportKind.Pdf) },
                    onSaveJpegs = { documentId -> exportDocument(documentId, PendingExportKind.Jpeg) },
                    onPrint = viewModel::print,
                    onShare = viewModel::share,
                    onSystemPreview = { document ->
                        selectedDestinationName = AppDestination.Home.name
                        openPrint(document.name, SystemPrintAdapter(document, context))
                    },
                )

                AppDestination.History -> HistoryScreen(uiState)
                AppDestination.Settings -> SettingsScreen(
                    uiState = uiState,
                    onModeChanged = viewModel::setIntegrationMode,
                    onFindDevices = viewModel::discoverDevices,
                )
            }
        }
    }
}
