package com.brianshih.mopria.android.scanprint.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode

internal enum class AppDestination(val labelRes: Int, val icon: ImageVector) {
    Home(R.string.nav_home, Icons.Filled.Home),
    Scan(R.string.nav_scan, Icons.Outlined.DocumentScanner),
    Print(R.string.nav_print, Icons.Outlined.Print),
    Documents(R.string.nav_documents, Icons.Filled.Description),
    History(R.string.nav_history, Icons.Filled.History),
    Settings(R.string.nav_settings, Icons.Filled.Settings),
}

private enum class PendingExportKind { Pdf, Jpeg }

private data class PendingExport(val documentId: String, val kind: PendingExportKind)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MopriaApp(viewModel: MopriaViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedLanguage = LanguageManager.currentOverride(context)
    var selectedDestinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var editingDocumentId by remember { mutableStateOf<String?>(null) }
    val selectedDestination = AppDestination.entries
        .firstOrNull { it.name == selectedDestinationName }
    val isTablet: Boolean = LocalConfiguration.current.screenWidthDp.let { it > 0 && it >= 600 }

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
            viewModel.reportMessage(
                resources.getString(R.string.event_open_print_failed, error.message ?: resources.getString(R.string.common_retry)),
            )
        }
    }

    val phonePrintLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val title = if (uris.size == 1) {
                resources.getString(R.string.print_phone_document)
            } else {
                resources.getString(R.string.print_phone_documents, uris.size)
            }
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
            viewModel.reportMessage(resources.getString(R.string.event_android9_storage_permission))
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
            val uri = request.uri.toUri()
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(request.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(sendIntent, resources.getString(R.string.documents_share) + " ${request.title}"))
            }.onFailure { error ->
                viewModel.reportMessage(
                    resources.getString(R.string.event_share_open_failed, error.message ?: resources.getString(R.string.common_retry)),
                )
            }
        }
    }

    Box {
        Scaffold(
        topBar = {
            if ((selectedDestination ?: AppDestination.Home) != AppDestination.Home) {
                TopAppBar(
                    title = { Text(stringResource((selectedDestination ?: AppDestination.Home).labelRes), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { selectedDestinationName = AppDestination.Home.name }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back_home))
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
                                contentDescription = stringResource(R.string.connection_mode),
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
            if (!isTablet) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    AppDestination.entries
                        .filter { it != AppDestination.Scan && it != AppDestination.Print }
                        .forEach { destination ->
                        NavigationBarItem(
                            selected = destination == selectedDestination,
                            onClick = { selectedDestinationName = destination.name },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (isTablet) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    AppDestination.entries
                        .filter { it != AppDestination.Scan && it != AppDestination.Print }
                        .forEach { destination ->
                            NavigationRailItem(
                                selected = destination == selectedDestination,
                                onClick = { selectedDestinationName = destination.name },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(if (isTablet) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding),
            ) {
                when (selectedDestination ?: AppDestination.Home) {
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
                    onPresetChange = viewModel::applyScanPreset,
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
                    onDeleteDocument = viewModel::deleteDocument,
                    onEditDocument = { editingDocumentId = it },
                )

                AppDestination.History -> HistoryScreen(uiState)
                AppDestination.Settings -> SettingsScreen(
                    uiState = uiState,
                    onModeChanged = viewModel::setIntegrationMode,
                    onPrintMethodChanged = viewModel::setPrintMethod,
                    onFindDevices = viewModel::discoverDevices,
                    selectedLanguage = selectedLanguage,
                    onLanguageChanged = { language ->
                        LanguageManager.setOverride(context, language)
                        (context as? Activity)?.recreate()
                    },
                )
                }
            }
        }
    }

    editingDocumentId?.let { docId ->
        uiState.documents.firstOrNull { it.id == docId }?.let { document ->
            EditScreen(
                document = document,
                onRotatePage = { pageId, degrees -> viewModel.rotatePage(docId, pageId, degrees) },
                onMovePage = { from, to -> viewModel.movePage(docId, from, to) },
                onDeletePage = { pageId -> viewModel.deletePage(docId, pageId) },
                onCropPage = { pageId, crop -> viewModel.cropPage(docId, pageId, crop) },
                onBack = { editingDocumentId = null },
            )
        }
    }

    uiState.directIppPrintPrompt?.let { prompt ->
        PrintOptionsSheet(
            prompt = prompt,
            onConfirm = viewModel::confirmDirectIppPrint,
            onDismiss = viewModel::dismissDirectIppPrintPrompt,
        )
    }
    }
}
