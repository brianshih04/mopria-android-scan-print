package com.brianshih.mopria.android.scanprint.ui

import android.app.Application
import android.content.Context
import android.print.PrintJob
import android.print.PrintJobInfo
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DeviceDiscovery
import com.brianshih.mopria.android.scanprint.domain.DeviceKind
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.IntegrationDevice
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MockIntegrationProvider
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.PrintProvider
import com.brianshih.mopria.android.scanprint.domain.RealIntegrationProvider
import com.brianshih.mopria.android.scanprint.domain.ScanAcquisitionProvider
import com.brianshih.mopria.android.scanprint.domain.ScanColorMode
import com.brianshih.mopria.android.scanprint.domain.ScanDocumentOrganizer
import com.brianshih.mopria.android.scanprint.domain.ScanInputSource
import com.brianshih.mopria.android.scanprint.domain.ScanOutputFormat
import com.brianshih.mopria.android.scanprint.domain.ScanSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ShareRequest(val uri: String, val title: String)

class MopriaViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mockProvider = MockIntegrationProvider()
    private val realProvider = RealIntegrationProvider(application)
    private val _uiState = MutableStateFlow(
        MopriaUiState(
            integrationMode = loadIntegrationMode(),
            scanSettings = loadScanSettings(),
        ),
    )
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _printRequests = MutableSharedFlow<MopriaDocument>(extraBufferCapacity = 4)
    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 4)
    private val scanExportService = ScanExportService(application)

    val uiState = _uiState.asStateFlow()
    val events = _events.asSharedFlow()
    val printRequests = _printRequests.asSharedFlow()
    internal val shareRequests = _shareRequests.asSharedFlow()

    private val localizedContext: Context
        get() = LanguageManager.wrap(getApplication<Application>())

    private fun text(@StringRes resourceId: Int, vararg arguments: Any): String =
        localizedContext.getString(resourceId, *arguments)

    init {
        viewModelScope.launch {
            try {
                val savedDocuments = scanExportService.loadSavedDocuments()
                if (savedDocuments.isNotEmpty()) {
                    _uiState.update { it.copy(documents = savedDocuments + it.documents) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _events.tryEmit(text(R.string.event_saved_documents_unavailable))
            }
        }
    }

    fun setIntegrationMode(mode: IntegrationMode) {
        if (_uiState.value.isBusy || _uiState.value.integrationMode == mode) return

        preferences.edit { putString(KEY_INTEGRATION_MODE, mode.name) }
        _uiState.update {
            it.copy(
                integrationMode = mode,
                devices = emptyList(),
            )
        }
        _events.tryEmit(text(R.string.event_mode_changed, text(mode.labelRes), text(mode.descriptionRes)))
        discoverDevices()
    }

    fun discoverDevices() {
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            _uiState.update { it.copy(isDiscovering = true) }
            try {
                val devices = providersFor(mode).discovery.discover()
                _uiState.update { it.copy(isDiscovering = false, devices = devices) }
                _events.tryEmit(discoveryMessage(mode, devices))
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isDiscovering = false) }
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isDiscovering = false, devices = emptyList()) }
                _events.tryEmit(text(R.string.event_discovery_failed, text(mode.labelRes), error.message ?: text(R.string.common_retry)))
            }
        }
    }

    fun updateScanSettings(settings: ScanSettings) {
        if (_uiState.value.isBusy) return
        preferences.edit {
            putString(KEY_SCAN_INPUT_SOURCE, settings.inputSource.name)
            putInt(KEY_SCAN_RESOLUTION, settings.resolutionDpi)
            putString(KEY_SCAN_COLOR_MODE, settings.colorMode.name)
            putInt(KEY_SCAN_MAX_PAGES, settings.maxPages.coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES))
            putBoolean(KEY_SCAN_COMBINE_PDF, settings.combineAsPdf)
        }
        _uiState.update { it.copy(scanSettings = settings) }
    }

    fun scan() {
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            var jobId: String? = null
            try {
                _uiState.update { it.copy(isDiscovering = true) }
                val providers = providersFor(mode)
                val cachedDevices = _uiState.value.devices
                val devices = if (cachedDevices.any { it.kind == DeviceKind.Scanner }) {
                    cachedDevices
                } else {
                    providers.discovery.discover()
                }
                val scanner = devices.firstOrNull { it.kind == DeviceKind.Scanner }
                if (scanner == null) {
                    _uiState.update {
                        it.copy(
                            isDiscovering = false,
                            devices = devices,
                            awaitingNextFlatbedPage = it.pendingFlatbedDocumentId != null,
                        )
                    }
                    _events.tryEmit(text(R.string.event_no_scanner, text(mode.labelRes)))
                    return@launch
                }

                val currentJobId = "scan-job-${System.currentTimeMillis()}"
                jobId = currentJobId
                val queuedJob = JobRecord(
                    id = currentJobId,
                    kind = JobKind.Scan,
                    title = text(R.string.scan_title),
                    targetLabel = scanner.name,
                    status = JobStatus.Queued,
                    progress = 0,
                )
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = currentJobId,
                        devices = devices,
                        jobs = listOf(queuedJob) + it.jobs,
                    )
                }
                updateJob(currentJobId, JobStatus.Running, 35, text(R.string.event_scan_working))
                val settings = _uiState.value.scanSettings
                val document = providers.scan.scan(scanner, settings)
                updateJob(currentJobId, JobStatus.Running, 85, text(R.string.event_scan_organizing))
                when {
                    settings.inputSource == ScanInputSource.Flatbed && settings.combineAsPdf -> {
                        val existingId = _uiState.value.pendingFlatbedDocumentId
                        val existing = existingId?.let { id -> _uiState.value.documents.firstOrNull { it.id == id } }
                        val merged = ScanDocumentOrganizer.appendFlatbedPage(existing, document)
                        val reachedLimit = merged.pages.size >= MAX_SCAN_PAGES
                        _uiState.update { state ->
                            state.copy(
                                activeJobId = null,
                                selectedDocumentId = merged.id,
                                documents = if (existing == null) {
                                    listOf(merged) + state.documents
                                } else {
                                    state.documents.map { if (it.id == merged.id) merged else it }
                                },
                                pendingFlatbedDocumentId = merged.id.takeUnless { reachedLimit },
                                awaitingNextFlatbedPage = !reachedLimit,
                            )
                        }
                        updateJob(currentJobId, JobStatus.Completed, 100, text(R.string.common_pages, merged.pages.size))
                        if (reachedLimit) {
                            _events.tryEmit(text(R.string.event_scan_limit, MAX_SCAN_PAGES))
                            saveScan(merged.id, ScanOutputFormat.Pdf)
                        } else {
                            _events.tryEmit(text(R.string.event_scan_page_done, merged.pages.size))
                        }
                    }
                    settings.inputSource == ScanInputSource.Adf && !settings.combineAsPdf -> {
                        val separateDocuments = ScanDocumentOrganizer.splitPages(document)
                        _uiState.update {
                            it.copy(
                                activeJobId = null,
                                selectedDocumentId = separateDocuments.first().id,
                                documents = separateDocuments + it.documents,
                            )
                        }
                        updateJob(currentJobId, JobStatus.Completed, 100, text(R.string.common_pages, document.pages.size))
                        _events.tryEmit(text(R.string.event_scan_separate_done, text(mode.labelRes), document.pages.size))
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                activeJobId = null,
                                selectedDocumentId = document.id,
                                documents = listOf(document) + it.documents,
                            )
                        }
                        updateJob(currentJobId, JobStatus.Completed, 100, text(R.string.common_pages, document.pages.size))
                        _events.tryEmit(text(R.string.event_scan_done, text(mode.labelRes), document.pages.size))
                        if (settings.inputSource == ScanInputSource.Adf && settings.combineAsPdf) {
                            saveScan(document.id, ScanOutputFormat.Pdf)
                        }
                    }
                }
            } catch (error: CancellationException) {
                jobId?.let { updateJob(it, JobStatus.Cancelled, 0, text(R.string.event_scan_cancelled)) }
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = null,
                        awaitingNextFlatbedPage = it.pendingFlatbedDocumentId != null,
                    )
                }
                throw error
            } catch (error: Exception) {
                jobId?.let { updateJob(it, JobStatus.Failed, 0, error.message ?: text(R.string.event_scan_failed, text(mode.labelRes), text(R.string.common_retry))) }
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = null,
                        awaitingNextFlatbedPage = it.pendingFlatbedDocumentId != null,
                    )
                }
                _events.tryEmit(text(R.string.event_scan_failed, text(mode.labelRes), error.message ?: text(R.string.common_retry)))
            }
        }
    }

    fun continueFlatbedScan() {
        val state = _uiState.value
        if (state.isBusy || !state.awaitingNextFlatbedPage || state.pendingFlatbedDocumentId == null) return
        _uiState.update { it.copy(awaitingNextFlatbedPage = false) }
        scan()
    }

    fun finishFlatbedScan() {
        val state = _uiState.value
        if (state.isBusy || !state.awaitingNextFlatbedPage) return
        val documentId = state.pendingFlatbedDocumentId ?: return
        _uiState.update {
            it.copy(
                pendingFlatbedDocumentId = null,
                awaitingNextFlatbedPage = false,
                selectedDocumentId = documentId,
            )
        }
        saveScan(documentId, ScanOutputFormat.Pdf)
    }

    fun createDemoDocument() {
        val document = demoDocument()
        _uiState.update {
            it.copy(
                selectedDocumentId = document.id,
                documents = listOf(document) + it.documents,
            )
        }
        _events.tryEmit(text(R.string.event_demo_created))
    }

    fun print(documentId: String? = null) {
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            var jobId: String? = null
            try {
                val document = documentId?.let { id -> _uiState.value.documents.firstOrNull { it.id == id } }
                    ?: _uiState.value.documents.firstOrNull()
                    ?: demoDocument()
                if (_uiState.value.documents.none { it.id == document.id }) {
                    _uiState.update { it.copy(documents = listOf(document) + it.documents) }
                }

                _uiState.update { it.copy(isDiscovering = true) }
                val providers = providersFor(mode)
                val cachedDevices = _uiState.value.devices
                val devices = if (cachedDevices.any { it.kind == DeviceKind.Printer }) {
                    cachedDevices
                } else {
                    providers.discovery.discover()
                }
                val printer = devices.firstOrNull { it.kind == DeviceKind.Printer }
                if (printer == null) {
                    if (mode == IntegrationMode.Real) {
                        // No IPP printer discovered: fall back to the Android system print preview.
                        _uiState.update { it.copy(isDiscovering = false, devices = devices, selectedDocumentId = document.id) }
                        _printRequests.emit(document)
                        _events.tryEmit(text(R.string.event_print_opening))
                        return@launch
                    }
                    _uiState.update { it.copy(isDiscovering = false, devices = devices) }
                    _events.tryEmit(text(R.string.event_no_printer, text(mode.labelRes)))
                    return@launch
                }

                val currentJobId = "print-job-${System.currentTimeMillis()}"
                jobId = currentJobId
                val queuedJob = JobRecord(
                    id = currentJobId,
                    kind = JobKind.Print,
                    title = document.name,
                    targetLabel = printer.name,
                    status = JobStatus.Queued,
                    progress = 0,
                )
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = currentJobId,
                        devices = devices,
                        selectedDocumentId = document.id,
                        jobs = listOf(queuedJob) + it.jobs,
                    )
                }
                updateJob(currentJobId, JobStatus.Running, 45, text(R.string.event_print_working))
                providers.print.print(printer, document)
                updateJob(currentJobId, JobStatus.Completed, 100, text(R.string.event_print_done))
                _uiState.update { it.copy(activeJobId = null) }
                _events.tryEmit(text(R.string.event_print_done))
            } catch (error: CancellationException) {
                jobId?.let { updateJob(it, JobStatus.Cancelled, 0, text(R.string.event_print_cancelled)) }
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                throw error
            } catch (error: Exception) {
                jobId?.let { updateJob(it, JobStatus.Failed, 0, error.message ?: text(R.string.event_print_failed, text(mode.labelRes), text(R.string.common_retry))) }
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                _events.tryEmit(text(R.string.event_print_failed, text(mode.labelRes), error.message ?: text(R.string.common_retry)))
            }
        }
    }

    fun export(documentId: String? = null) {
        saveScan(documentId, ScanOutputFormat.Pdf)
    }

    fun saveJpegs(documentId: String? = null) {
        saveScan(documentId, ScanOutputFormat.Jpeg)
    }

    fun share(documentId: String) {
        if (_uiState.value.isBusy) return
        val document = _uiState.value.documents.firstOrNull { it.id == documentId }
        if (document == null) {
            _events.tryEmit(text(R.string.event_share_missing))
            return
        }
        viewModelScope.launch {
            try {
                val uri = scanExportService.createSharePdf(document)
                _shareRequests.emit(ShareRequest(uri.toString(), document.name))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _events.tryEmit(text(R.string.event_share_failed, error.message ?: text(R.string.common_retry)))
            }
        }
    }

    private fun saveScan(documentId: String?, format: ScanOutputFormat) {
        if (_uiState.value.isBusy) return

        val document = documentId?.let { id -> _uiState.value.documents.firstOrNull { it.id == id } }
            ?: _uiState.value.documents.firstOrNull()
        if (document == null) {
            _events.tryEmit(text(R.string.common_no_documents))
            return
        }

        viewModelScope.launch {
            val jobId = "export-job-${System.currentTimeMillis()}"
            val queuedJob = JobRecord(
                id = jobId,
                kind = JobKind.Export,
                title = document.name,
                targetLabel = "Download/Mopria Scan & Print/Scans",
                status = JobStatus.Running,
                progress = 25,
            )
            _uiState.update { it.copy(activeJobId = jobId, jobs = listOf(queuedJob) + it.jobs) }

            try {
                val file = scanExportService.save(document, format)
                val updatedDocument = document.copy(
                    exportedPath = file.firstOrNull()?.location,
                    savedFiles = document.savedFiles + file,
                )
                _uiState.update {
                    it.copy(
                        activeJobId = null,
                        lastExportPath = file.firstOrNull()?.location,
                        documents = it.documents.map { item ->
                            if (item.id == updatedDocument.id) updatedDocument else item
                        },
                    )
                }
                updateJob(jobId, JobStatus.Completed, 100, text(R.string.event_export_done, file.size, text(format.labelRes)))
                _events.tryEmit(text(R.string.event_export_location, text(format.labelRes)))
            } catch (error: CancellationException) {
                updateJob(jobId, JobStatus.Cancelled, 0, text(R.string.event_export_cancelled, text(format.labelRes)))
                _uiState.update { it.copy(activeJobId = null) }
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(activeJobId = null) }
                updateJob(jobId, JobStatus.Failed, 0, error.message ?: text(R.string.event_export_failed, text(format.labelRes)))
                _events.tryEmit(text(R.string.event_export_failed, text(format.labelRes)))
            }
        }
    }

    fun monitorSystemPrint(printJob: PrintJob, title: String) {
        val jobId = "system-print-${System.currentTimeMillis()}"
        val queuedJob = JobRecord(
            id = jobId,
            kind = JobKind.Print,
            title = title,
            targetLabel = "Android Print Framework",
            status = JobStatus.Queued,
            progress = 0,
            detail = text(R.string.event_print_opening),
        )
        _uiState.update { it.copy(jobs = listOf(queuedJob) + it.jobs) }
        viewModelScope.launch {
            while (!printJob.isCompleted && !printJob.isFailed && !printJob.isCancelled) {
                val info = printJob.info
                val running = info.state == PrintJobInfo.STATE_STARTED || info.state == PrintJobInfo.STATE_BLOCKED
                updateJob(
                    jobId,
                    if (running) JobStatus.Running else JobStatus.Queued,
                    if (running) 50 else 15,
                    when (info.state) {
                        PrintJobInfo.STATE_BLOCKED -> text(R.string.event_system_print_blocked)
                        PrintJobInfo.STATE_STARTED -> text(R.string.event_system_print_started)
                        else -> text(R.string.event_system_print_waiting)
                    },
                )
                delay(500)
            }
            val finalStatus = when {
                printJob.isCompleted -> JobStatus.Completed
                printJob.isCancelled -> JobStatus.Cancelled
                else -> JobStatus.Failed
            }
            updateJob(jobId, finalStatus, if (finalStatus == JobStatus.Completed) 100 else 0, when (finalStatus) {
                JobStatus.Completed -> text(R.string.event_system_print_done)
                JobStatus.Cancelled -> text(R.string.event_system_print_cancelled)
                else -> text(R.string.event_system_print_failed)
            })
        }
    }

    fun reportMessage(message: String) {
        _events.tryEmit(message)
    }

    private fun updateJob(id: String, status: JobStatus, progress: Int, detail: String) {
        _uiState.update {
            it.copy(
                jobs = it.jobs.map { job ->
                    if (job.id == id) job.copy(status = status, progress = progress, detail = detail) else job
                },
                activeJobId = if (
                    status == JobStatus.Completed || status == JobStatus.Failed || status == JobStatus.Cancelled
                ) null else it.activeJobId,
            )
        }
    }

    private fun providersFor(mode: IntegrationMode): ProviderSet = when (mode) {
        IntegrationMode.Mock -> ProviderSet(mockProvider, mockProvider, mockProvider)
        IntegrationMode.Real -> ProviderSet(realProvider, realProvider, realProvider)
    }

    private fun loadIntegrationMode(): IntegrationMode = preferences
        .getString(KEY_INTEGRATION_MODE, IntegrationMode.Mock.name)
        ?.let { value -> runCatching { IntegrationMode.valueOf(value) }.getOrDefault(IntegrationMode.Mock) }
        ?: IntegrationMode.Mock

    private fun loadScanSettings(): ScanSettings = ScanSettings(
        inputSource = preferences.getString(KEY_SCAN_INPUT_SOURCE, ScanInputSource.Flatbed.name)
            ?.let { value -> runCatching { ScanInputSource.valueOf(value) }.getOrDefault(ScanInputSource.Flatbed) }
            ?: ScanInputSource.Flatbed,
        resolutionDpi = preferences.getInt(KEY_SCAN_RESOLUTION, 300)
            .takeIf { it in SUPPORTED_RESOLUTIONS }
            ?: 300,
        colorMode = preferences.getString(KEY_SCAN_COLOR_MODE, ScanColorMode.Color.name)
            ?.let { value -> runCatching { ScanColorMode.valueOf(value) }.getOrDefault(ScanColorMode.Color) }
            ?: ScanColorMode.Color,
        maxPages = preferences.getInt(KEY_SCAN_MAX_PAGES, DEFAULT_SCAN_MAX_PAGES)
            .coerceIn(MIN_SCAN_PAGES, MAX_SCAN_PAGES),
        combineAsPdf = preferences.getBoolean(KEY_SCAN_COMBINE_PDF, false),
    )

    private fun discoveryMessage(mode: IntegrationMode, devices: List<IntegrationDevice>): String = when {
        mode == IntegrationMode.Mock -> text(R.string.event_discovery_mock, devices.size)
        devices.isEmpty() -> text(R.string.event_discovery_none)
        else -> text(R.string.event_discovery_real, devices.size)
    }

    private fun demoDocument(): MopriaDocument {
        val timestamp = System.currentTimeMillis()
        return MopriaDocument(
            id = "demo-$timestamp",
            name = text(R.string.document_demo_name, timestamp.toString().takeLast(4)),
            sourceLabel = text(R.string.document_demo_source),
            pages = listOf(
                DocumentPage("$timestamp-demo-1", 1, text(R.string.document_demo_cover)),
                DocumentPage("$timestamp-demo-2", 2, text(R.string.document_demo_content)),
            ),
            createdAt = timestamp,
        )
    }

    private data class ProviderSet(
        val discovery: DeviceDiscovery,
        val scan: ScanAcquisitionProvider,
        val print: PrintProvider,
    )

    private companion object {
        const val PREFERENCES_NAME = "mopria_settings"
        const val KEY_INTEGRATION_MODE = "integration_mode"
        const val KEY_SCAN_INPUT_SOURCE = "scan_input_source"
        const val KEY_SCAN_RESOLUTION = "scan_resolution"
        const val KEY_SCAN_COLOR_MODE = "scan_color_mode"
        const val KEY_SCAN_MAX_PAGES = "scan_max_pages"
        const val KEY_SCAN_COMBINE_PDF = "scan_combine_pdf"
        const val MIN_SCAN_PAGES = 1
        const val MAX_SCAN_PAGES = 50
        const val DEFAULT_SCAN_MAX_PAGES = 20
        val SUPPORTED_RESOLUTIONS = setOf(150, 300, 600)
    }
}
