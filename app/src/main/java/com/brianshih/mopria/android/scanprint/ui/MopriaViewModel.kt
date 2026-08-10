package com.brianshih.mopria.android.scanprint.ui

import android.app.Application
import android.content.Context
import android.print.PrintJob
import android.print.PrintJobInfo
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brianshih.mopria.android.scanprint.R
import com.brianshih.mopria.android.scanprint.domain.DeviceDiscovery
import com.brianshih.mopria.android.scanprint.domain.DirectIppPrintPrompt
import com.brianshih.mopria.android.scanprint.domain.DeviceKind
import com.brianshih.mopria.android.scanprint.domain.DocumentPage
import com.brianshih.mopria.android.scanprint.domain.CropRect
import com.brianshih.mopria.android.scanprint.domain.DocumentEditor
import com.brianshih.mopria.android.scanprint.domain.EnhancementError
import com.brianshih.mopria.android.scanprint.domain.EnhancementResult
import com.brianshih.mopria.android.scanprint.domain.EnhancementSkipReason
import com.brianshih.mopria.android.scanprint.domain.DocumentStore
import com.brianshih.mopria.android.scanprint.domain.IntegrationDevice
import com.brianshih.mopria.android.scanprint.domain.IntegrationMode
import com.brianshih.mopria.android.scanprint.domain.JobKind
import com.brianshih.mopria.android.scanprint.domain.JobRecord
import com.brianshih.mopria.android.scanprint.domain.JobStatus
import com.brianshih.mopria.android.scanprint.domain.MockIntegrationProvider
import com.brianshih.mopria.android.scanprint.domain.MopriaDocument
import com.brianshih.mopria.android.scanprint.domain.MopriaUiState
import com.brianshih.mopria.android.scanprint.domain.OcrError
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePack
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePackManager
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePackState
import com.brianshih.mopria.android.scanprint.domain.OcrLanguagePackStatus
import com.brianshih.mopria.android.scanprint.domain.OcrLanguageModel
import com.brianshih.mopria.android.scanprint.domain.OcrMode
import com.brianshih.mopria.android.scanprint.domain.OcrPackDownloadResult
import com.brianshih.mopria.android.scanprint.domain.OcrResult
import com.brianshih.mopria.android.scanprint.domain.OcrSkipReason
import com.brianshih.mopria.android.scanprint.domain.PrintMethod
import com.brianshih.mopria.android.scanprint.domain.PrintError
import com.brianshih.mopria.android.scanprint.domain.ScanError
import com.brianshih.mopria.android.scanprint.domain.PrintOptions
import com.brianshih.mopria.android.scanprint.domain.PrintProvider
import com.brianshih.mopria.android.scanprint.domain.RealIntegrationProvider
import com.brianshih.mopria.android.scanprint.domain.ScanAcquisitionProvider
import com.brianshih.mopria.android.scanprint.domain.ScanColorMode
import com.brianshih.mopria.android.scanprint.domain.ScanDocumentOrganizer
import com.brianshih.mopria.android.scanprint.domain.ScanInputSource
import com.brianshih.mopria.android.scanprint.domain.ScanImageError
import com.brianshih.mopria.android.scanprint.domain.ScanImageResult
import com.brianshih.mopria.android.scanprint.domain.ScanImageSkipReason
import com.brianshih.mopria.android.scanprint.domain.ScanOutputFormat
import com.brianshih.mopria.android.scanprint.domain.ScanPreset
import com.brianshih.mopria.android.scanprint.domain.ScanProgress
import com.brianshih.mopria.android.scanprint.domain.ScanProgressStage
import com.brianshih.mopria.android.scanprint.domain.ScanSettings
import com.brianshih.mopria.android.scanprint.domain.ScannerCapabilities
import com.brianshih.mopria.android.scanprint.domain.TempFileCleanup
import com.brianshih.mopria.android.scanprint.domain.SettingsStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ShareRequest(val uri: String, val title: String)

class MopriaViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)
    private val ocrLanguagePackManager = OcrLanguagePackManager(application)
    private val mockProvider = MockIntegrationProvider()
    private val realProvider = RealIntegrationProvider(application)
    private val initialOcrLanguagePacks = settingsStore.loadOcrLanguagePacks()
    private val initialScanSettings = settingsStore.loadScanSettings()
    private val _uiState = MutableStateFlow(
        MopriaUiState(
            integrationMode = settingsStore.loadIntegrationMode(),
            printMethod = settingsStore.loadPrintMethod(),
            scanSettings = initialScanSettings,
            scanPreset = settingsStore.loadScanPreset(),
            ocrLanguagePacks = ocrLanguagePackManager.states(
                selected = initialOcrLanguagePacks,
                active = initialScanSettings.ocrLanguage,
            ),
        ),
    )
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _printRequests = MutableSharedFlow<MopriaDocument>(extraBufferCapacity = 4)
    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 4)
    private val scanExportService = ScanExportService(application)
    private val documentStore = DocumentStore(application)
    private val documentStoreReady = CompletableDeferred<Unit>()
    private val documentPersistenceMutex = Mutex()
    private val documentPersistenceGeneration = AtomicLong()

    val uiState = _uiState.asStateFlow()
    val events = _events.asSharedFlow()
    val printRequests = _printRequests.asSharedFlow()
    internal val shareRequests = _shareRequests.asSharedFlow()

    private val localizedContext: Context
        get() = LanguageManager.wrap(getApplication<Application>())

    private fun text(@StringRes resourceId: Int, vararg arguments: Any): String =
        localizedContext.getString(resourceId, *arguments)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Restore documents and OCR sidecars off the main thread. Merge with any state created
                // during startup instead of replacing it if the user acts before disk loading finishes.
                documentStore.load()?.let { persisted ->
                    _uiState.update { current ->
                        val currentIds = current.documents.mapTo(mutableSetOf()) { it.id }
                        val restored = persisted.documents.filter { it.id !in currentIds }
                        val pendingId = current.pendingFlatbedDocumentId ?: persisted.pendingFlatbedDocumentId
                        current.copy(
                            documents = current.documents + restored,
                            pendingFlatbedDocumentId = pendingId,
                            awaitingNextFlatbedPage = pendingId != null,
                        )
                    }
                }

                // Reclaim storage only after restored documents have contributed their referenced paths.
                val referencedPaths = _uiState.value.documents
                    .flatMap { it.pages }
                    .flatMap { listOfNotNull(it.imagePath, it.pdfPath) }
                    .map { it.removePrefix("file://") }
                    .toSet()
                TempFileCleanup.deleteOrphanedScans(application, referencedPaths)
                TempFileCleanup.sweepStaleCache(application)
            } finally {
                documentStoreReady.complete(Unit)
            }

            // Then load any additional documents saved to the public Downloads folder via MediaStore.
            try {
                val savedDocuments = scanExportService.loadSavedDocuments()
                if (savedDocuments.isNotEmpty()) {
                    val existingIds = _uiState.value.documents.mapTo(mutableSetOf()) { it.id }
                    val newDocs = savedDocuments.filter { it.id !in existingIds }
                    if (newDocs.isNotEmpty()) {
                        _uiState.update { it.copy(documents = it.documents + newDocs) }
                    }
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

        settingsStore.saveIntegrationMode(mode)
        _uiState.update {
            it.copy(
                integrationMode = mode,
                devices = emptyList(),
            )
        }
        _events.tryEmit(text(R.string.event_mode_changed, text(mode.labelRes), text(mode.descriptionRes)))
        discoverDevices()
    }

    fun setPrintMethod(method: PrintMethod) {
        if (_uiState.value.printMethod == method) return
        settingsStore.savePrintMethod(method)
        _uiState.update { it.copy(printMethod = method) }
    }

    fun setOcrLanguagePackSelected(language: OcrLanguagePack, selected: Boolean) {
        if (_uiState.value.isBusy || language.model == OcrLanguageModel.Unsupported) return
        val current = _uiState.value.ocrLanguagePacks
            .filter { it.selected && it.language.model != OcrLanguageModel.Unsupported }
            .mapTo(linkedSetOf()) { it.language }
        val next = current.toMutableSet().apply {
            if (selected) add(language) else remove(language)
        }
        if (next.isEmpty()) return
        val active = _uiState.value.scanSettings.ocrLanguage
            .takeIf { it in next }
            ?: next.first()
        settingsStore.saveOcrLanguagePacks(next)
        val updatedSettings = _uiState.value.scanSettings.copy(ocrLanguage = active)
        settingsStore.saveScanSettings(updatedSettings)
        _uiState.update {
            it.copy(
                scanSettings = updatedSettings,
                ocrLanguagePacks = ocrLanguagePackManager.states(next, active),
            )
        }
    }

    fun setActiveOcrLanguage(language: OcrLanguagePack) {
        if (_uiState.value.isBusy || _uiState.value.ocrLanguagePacks.none {
                it.language == language && it.selected
            }
        ) return
        updateScanSettings(_uiState.value.scanSettings.copy(ocrLanguage = language))
    }

    fun downloadOcrLanguagePack(language: OcrLanguagePack) {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { downloadOcrLanguagePackInternal(language) }
    }

    fun downloadSelectedOcrLanguagePacks() {
        if (_uiState.value.isBusy) return
        viewModelScope.launch { downloadSelectedOcrLanguagePacksInternal() }
    }

    private suspend fun downloadSelectedOcrLanguagePacksInternal() {
        val selected = _uiState.value.ocrLanguagePacks
            .filter { it.selected && it.language.model != OcrLanguageModel.Unsupported }
            .map { it.language }
            .distinctBy { it.model }
        selected.forEach { language -> downloadOcrLanguagePackInternal(language) }
        refreshOcrLanguagePackStates()
    }

    private suspend fun downloadOcrLanguagePackInternal(language: OcrLanguagePack) {
        val current = _uiState.value.ocrLanguagePacks.firstOrNull { it.language == language }
        if (current?.status == OcrLanguagePackStatus.Downloading) return
        updateOcrLanguagePackState(language) { it.copy(status = OcrLanguagePackStatus.Downloading, progress = 0) }
        when (val result = ocrLanguagePackManager.download(language) { progress ->
            updateOcrLanguagePackState(language) { it.copy(progress = progress) }
        }) {
            is OcrPackDownloadResult.Ready -> {
                refreshOcrLanguagePackStates()
                _events.tryEmit(text(R.string.event_ocr_language_pack_downloaded, text(language.labelRes)))
            }
            is OcrPackDownloadResult.Requested -> {
                refreshOcrLanguagePackStates()
                _events.tryEmit(text(R.string.event_ocr_language_pack_unavailable, text(language.labelRes)))
            }
            is OcrPackDownloadResult.Failed -> {
                updateOcrLanguagePackState(language) {
                    it.copy(status = OcrLanguagePackStatus.Failed, progress = 0)
                }
                _events.tryEmit(text(R.string.event_ocr_language_pack_failed, text(language.labelRes)))
            }
        }
    }

    private fun updateOcrLanguagePackState(
        language: OcrLanguagePack,
        transform: (OcrLanguagePackState) -> OcrLanguagePackState,
    ) {
        _uiState.update { state ->
            state.copy(
                ocrLanguagePacks = state.ocrLanguagePacks.map { pack ->
                    if (pack.language == language) transform(pack) else pack
                },
            )
        }
    }

    private fun refreshOcrLanguagePackStates() {
        val state = _uiState.value
        val selected = state.ocrLanguagePacks
            .filter { it.selected && it.language.model != OcrLanguageModel.Unsupported }
            .mapTo(linkedSetOf()) { it.language }
        _uiState.update {
            it.copy(ocrLanguagePacks = ocrLanguagePackManager.states(selected, it.scanSettings.ocrLanguage))
        }
    }

    fun discoverDevices() {
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            _uiState.update { it.copy(isDiscovering = true) }
            try {
                val providers = providersFor(mode)
                val devices = providers.discovery.discover()
                // In Real mode, fetch scanner capabilities so the scan settings UI shows
                // only options the scanner actually supports.
                val caps = if (mode == IntegrationMode.Real) {
                    devices.firstOrNull { it.kind == DeviceKind.Scanner }
                        ?.let { scanner -> fetchScannerCapabilities(providers.scan, scanner) }
                } else {
                    ScannerCapabilities.DEFAULT
                }
                val resolvedCaps = caps ?: ScannerCapabilities.DEFAULT
                val currentSettings = _uiState.value.scanSettings
                val adjustedSettings = resolvedCaps.reconcile(currentSettings)
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        devices = devices,
                        scannerCapabilities = resolvedCaps,
                        scanSettings = adjustedSettings,
                    )
                }
                if (adjustedSettings != currentSettings) settingsStore.saveScanSettings(adjustedSettings)
                notifyEnhancementDisabled(currentSettings, adjustedSettings)
                notifyOcrDisabled(currentSettings, adjustedSettings)
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


    fun applyScanPreset(preset: ScanPreset) {
        val current = _uiState.value.scanSettings
        val defaults = preset.defaultSettings(current)
        val adjusted = _uiState.value.scannerCapabilities.reconcile(defaults)
        _uiState.update { it.copy(scanPreset = preset, scanSettings = adjusted) }
        settingsStore.saveScanSettings(adjusted)
        settingsStore.saveScanPreset(preset)
        notifyEnhancementDisabled(defaults, adjusted)
        notifyOcrDisabled(defaults, adjusted)
    }

    fun updateScanSettings(settings: ScanSettings) {
        if (_uiState.value.isBusy) return
        val wasOcrEnabled = _uiState.value.scanSettings.ocrMode != OcrMode.Disabled
        val adjusted = _uiState.value.scannerCapabilities.reconcile(settings)
        val selected = _uiState.value.ocrLanguagePacks
            .filter { it.selected && it.language.model != OcrLanguageModel.Unsupported }
            .mapTo(linkedSetOf()) { it.language }
            .ifEmpty { OcrLanguagePack.defaultSelection }
        val safeLanguage = adjusted.ocrLanguage.takeIf { it in selected } ?: selected.first()
        val safeSettings = adjusted.copy(ocrLanguage = safeLanguage)
        settingsStore.saveScanSettings(safeSettings)
        _uiState.update {
            it.copy(
                scanSettings = safeSettings,
                ocrLanguagePacks = ocrLanguagePackManager.states(selected, safeLanguage),
            )
        }
        notifyEnhancementDisabled(settings, safeSettings)
        notifyOcrDisabled(settings, safeSettings)
        if (!wasOcrEnabled && safeSettings.ocrMode != OcrMode.Disabled) {
            downloadSelectedOcrLanguagePacks()
        }
    }

    fun scan() {
        if (_uiState.value.isBusy) return

        // Claim the scan slot before launching so a second tap cannot start another coroutine
        // while optional ML Kit modules are being requested.
        _uiState.update { it.copy(isDiscovering = true) }

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            var jobId: String? = null
            try {
                if (_uiState.value.scanSettings.ocrMode != OcrMode.Disabled) {
                    downloadSelectedOcrLanguagePacksInternal()
                }
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

                val scannerCaps = if (mode == IntegrationMode.Real) {
                    fetchScannerCapabilities(providers.scan, scanner) ?: _uiState.value.scannerCapabilities
                } else {
                    ScannerCapabilities.DEFAULT
                }
                val requestedSettings = _uiState.value.scanSettings
                val settings = scannerCaps.reconcile(requestedSettings)
                _uiState.update {
                    it.copy(
                        scannerCapabilities = scannerCaps,
                        scanSettings = settings,
                    )
                }
                if (settings != requestedSettings) settingsStore.saveScanSettings(settings)
                notifyEnhancementDisabled(requestedSettings, settings)
                notifyOcrDisabled(requestedSettings, settings)

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
                val document = providers.scan.scan(scanner, settings) { progress ->
                    val (percentage, detail) = progress.toJobUpdate()
                    updateJob(currentJobId, JobStatus.Running, percentage, detail)
                }
                document.enhancementResults.distinct().forEach { result ->
                    when (result) {
                        EnhancementResult.Applied -> Unit
                        is EnhancementResult.Skipped -> _events.tryEmit(
                            text(R.string.event_scan_enhancement_skipped, text(result.reason.messageStringRes())),
                        )
                        is EnhancementResult.Failed -> _events.tryEmit(
                            text(R.string.event_scan_enhancement_failed, text(result.error.messageStringRes())),
                        )
                    }
                }
                document.imageProcessingResults.distinct().forEach { result ->
                    when (result) {
                        ScanImageResult.Applied,
                        ScanImageResult.Unchanged,
                        -> Unit
                        ScanImageResult.DroppedBlankPage -> _events.tryEmit(text(R.string.event_scan_blank_page_dropped))
                        is ScanImageResult.Skipped -> _events.tryEmit(
                            text(R.string.event_scan_image_processing_skipped, text(result.reason.messageStringRes())),
                        )
                        is ScanImageResult.Failed -> _events.tryEmit(
                            text(R.string.event_scan_image_processing_failed, text(result.error.messageStringRes())),
                        )
                    }
                }
                document.ocrResults.distinct().forEach { result ->
                    when (result) {
                        is OcrResult.Applied -> Unit
                        is OcrResult.Skipped -> _events.tryEmit(
                            text(R.string.event_scan_ocr_skipped, text(result.reason.messageStringRes())),
                        )
                        is OcrResult.Failed -> _events.tryEmit(
                            text(R.string.event_scan_ocr_failed, text(result.error.messageStringRes())),
                        )
                    }
                }
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
                        persistDocuments()
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
                        persistDocuments()
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
                        persistDocuments()
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
            } catch (error: ScanError) {
                val message = text(error.messageStringRes())
                jobId?.let { updateJob(it, JobStatus.Failed, 0, message) }
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = null,
                        awaitingNextFlatbedPage = it.pendingFlatbedDocumentId != null,
                    )
                }
                _events.tryEmit(message)
            } catch (error: Exception) {
                val message = text(R.string.event_scan_failed, text(mode.labelRes), text(R.string.common_retry))
                jobId?.let { updateJob(it, JobStatus.Failed, 0, message) }
                _uiState.update {
                    it.copy(
                        isDiscovering = false,
                        activeJobId = null,
                        awaitingNextFlatbedPage = it.pendingFlatbedDocumentId != null,
                    )
                }
                _events.tryEmit(message)
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
        persistDocuments()
    }

    fun deleteDocument(documentId: String) {
        val document = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        TempFileCleanup.deleteDocumentFiles(getApplication(), document)
        _uiState.update { state ->
            state.copy(
                documents = state.documents.filterNot { it.id == documentId },
                selectedDocumentId = if (state.selectedDocumentId == documentId) null else state.selectedDocumentId,
            )
        }
        persistDocuments()
        _events.tryEmit(text(R.string.event_document_deleted))
    }


    fun rotatePage(documentId: String, pageId: String, degrees: Int) {
        val document = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        val edited = DocumentEditor.rotatePage(document, pageId, degrees)
        updateDocument(edited)
    }

    fun movePage(documentId: String, fromIndex: Int, toIndex: Int) {
        val document = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        val edited = DocumentEditor.movePage(document, fromIndex, toIndex)
        updateDocument(edited)
    }

    fun deletePage(documentId: String, pageId: String) {
        val document = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        val edited = DocumentEditor.deletePage(document, pageId)
        if (edited != null) {
            updateDocument(edited)
        } else {
            // Last page deleted — delete the whole document
            deleteDocument(documentId)
        }
    }

    fun cropPage(documentId: String, pageId: String, crop: CropRect?) {
        val document = _uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        val edited = DocumentEditor.cropPage(document, pageId, crop)
        updateDocument(edited)
    }

    private fun updateDocument(document: MopriaDocument) {
        _uiState.update { state ->
            state.copy(
                documents = state.documents.map { if (it.id == document.id) document else it },
            )
        }
        persistDocuments()
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
            try {
                val document = documentId?.let { id -> _uiState.value.documents.firstOrNull { it.id == id } }
                    ?: _uiState.value.documents.firstOrNull()
                    ?: demoDocument()
                if (_uiState.value.documents.none { it.id == document.id }) {
                    _uiState.update { it.copy(documents = listOf(document) + it.documents) }
                }

                if (mode == IntegrationMode.Real && _uiState.value.printMethod == PrintMethod.System) {
                    _uiState.update { it.copy(selectedDocumentId = document.id) }
                    _printRequests.emit(document)
                    _events.tryEmit(text(R.string.event_print_opening))
                    return@launch
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
                    // Direct IPP found no printer: do NOT silently fall back to system print — that
                    // would mislead users into thinking Direct IPP was used. Surface an explicit error
                    // and let them switch print method in Settings if they want system print.
                    _uiState.update { it.copy(isDiscovering = false, devices = devices) }
                    _events.tryEmit(
                        if (mode == IntegrationMode.Real) text(R.string.event_no_ipp_printer)
                        else text(R.string.event_no_printer, text(mode.labelRes)),
                    )
                    return@launch
                }
                _uiState.update { it.copy(isDiscovering = false, devices = devices) }

                // Direct IPP: if the printer advertises configurable options, let the user choose first.
                if (_uiState.value.printMethod == PrintMethod.Ipp) {
                    val capabilities = providers.print.capabilities(printer)
                    if (capabilities != null && capabilities.hasAnyOption()) {
                        _uiState.update {
                            it.copy(directIppPrintPrompt = DirectIppPrintPrompt(document, printer, capabilities))
                        }
                        return@launch
                    }
                }

                submitDirectIpp(document, printer, options = null, mode)
            } catch (error: CancellationException) {
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                throw error
            } catch (_: Exception) {
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                _events.tryEmit(text(R.string.event_print_failed, text(mode.labelRes), text(R.string.common_retry)))
            }
        }
    }

    /** User confirmed the Direct IPP options sheet — submit with the chosen [options]. */
    fun confirmDirectIppPrint(options: PrintOptions) {
        val prompt = _uiState.value.directIppPrintPrompt ?: return
        _uiState.update { it.copy(directIppPrintPrompt = null) }
        viewModelScope.launch {
            submitDirectIpp(prompt.document, prompt.printer, options, _uiState.value.integrationMode)
        }
    }

    /** User dismissed the Direct IPP options sheet. */
    fun dismissDirectIppPrintPrompt() {
        _uiState.update { it.copy(directIppPrintPrompt = null) }
    }

    private suspend fun submitDirectIpp(
        document: MopriaDocument,
        printer: IntegrationDevice,
        options: PrintOptions?,
        mode: IntegrationMode,
    ) {
        val providers = providersFor(mode)
        var jobId: String? = null
        try {
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
                    activeJobId = currentJobId,
                    selectedDocumentId = document.id,
                    jobs = listOf(queuedJob) + it.jobs,
                )
            }
            updateJob(currentJobId, JobStatus.Running, 45, text(R.string.event_print_working))
            providers.print.print(printer, document, options)
            updateJob(currentJobId, JobStatus.Completed, 100, text(R.string.event_print_done))
            _uiState.update { it.copy(activeJobId = null) }
            _events.tryEmit(text(R.string.event_print_done))
        } catch (error: CancellationException) {
            jobId?.let { updateJob(it, JobStatus.Cancelled, 0, text(R.string.event_print_cancelled)) }
            _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
            throw error
        } catch (error: PrintError) {
            val message = text(error.messageStringRes())
            jobId?.let { updateJob(it, JobStatus.Failed, 0, message) }
            _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
            _events.tryEmit(message)
        } catch (error: Exception) {
            val message = text(R.string.event_print_failed, text(mode.labelRes), text(R.string.common_retry))
            jobId?.let { updateJob(it, JobStatus.Failed, 0, message) }
            _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
            _events.tryEmit(message)
        }
    }

    /** Maps a localizable [PrintError] to the string resource that describes it for the user. */
    private fun PrintError.messageStringRes(): Int = when (this) {
        is PrintError.UnsupportedFormat -> R.string.print_error_unsupported_format
        is PrintError.CreateJobFailed -> R.string.print_error_rejected
        is PrintError.SendDocumentFailed -> R.string.print_error_rejected
        is PrintError.MultipleDocumentsUnsupported -> R.string.print_error_rejected
        is PrintError.JobCanceled -> R.string.print_error_job_stopped
        is PrintError.JobAborted -> R.string.print_error_job_stopped
        is PrintError.JobTimeout -> R.string.print_error_timeout
        is PrintError.PageRenderFailed -> R.string.print_error_render_failed
    }

    /** Maps a localizable [ScanError] to the string resource that describes it for the user. */
    private fun ScanError.messageStringRes(): Int = when (this) {
        is ScanError.ScannerNotReady -> R.string.scan_error_scanner_not_ready
        is ScanError.AdfNotReady -> R.string.scan_error_adf_not_ready
        is ScanError.HttpError -> R.string.scan_error_http
        is ScanError.DocumentTimeout -> R.string.scan_error_timeout
        is ScanError.JobAborted -> R.string.scan_error_job_stopped
        is ScanError.JobTimeout -> R.string.scan_error_timeout
        is ScanError.CapabilityNotSupported -> R.string.scan_error_capability
        is ScanError.OcrImageFormatUnsupported -> R.string.scan_error_ocr_image_format
        is ScanError.NoImages -> R.string.scan_error_no_images
    }

    private fun EnhancementSkipReason.messageStringRes(): Int = when (this) {
        EnhancementSkipReason.OpenCvUnavailable -> R.string.scan_enhancement_reason_unavailable
        EnhancementSkipReason.UnsupportedPdf -> R.string.scan_enhancement_reason_pdf
        EnhancementSkipReason.UnsupportedFormat -> R.string.scan_enhancement_reason_format
        EnhancementSkipReason.ImageTooLarge -> R.string.scan_enhancement_reason_large
    }

    private fun EnhancementError.messageStringRes(): Int = when (this) {
        EnhancementError.InvalidSource -> R.string.scan_enhancement_reason_invalid
        EnhancementError.DecodeFailed -> R.string.scan_enhancement_reason_decode
        EnhancementError.ProcessingFailed -> R.string.scan_enhancement_reason_processing
        EnhancementError.EncodeFailed -> R.string.scan_enhancement_reason_encode
        EnhancementError.OutputValidationFailed -> R.string.scan_enhancement_reason_output
        EnhancementError.ReplaceFailed -> R.string.scan_enhancement_reason_replace
    }

    private fun ScanImageSkipReason.messageStringRes(): Int = when (this) {
        ScanImageSkipReason.OpenCvUnavailable -> R.string.scan_image_reason_unavailable
        ScanImageSkipReason.UnsupportedPdf -> R.string.scan_image_reason_pdf
        ScanImageSkipReason.UnsupportedFormat -> R.string.scan_image_reason_format
        ScanImageSkipReason.ImageTooLarge -> R.string.scan_image_reason_large
    }

    private fun ScanImageError.messageStringRes(): Int = when (this) {
        ScanImageError.InvalidSource -> R.string.scan_image_reason_invalid
        ScanImageError.DecodeFailed -> R.string.scan_image_reason_decode
        ScanImageError.ProcessingFailed -> R.string.scan_image_reason_processing
        ScanImageError.EncodeFailed -> R.string.scan_image_reason_encode
        ScanImageError.OutputValidationFailed -> R.string.scan_image_reason_output
        ScanImageError.ReplaceFailed -> R.string.scan_image_reason_replace
    }

    private fun OcrSkipReason.messageStringRes(): Int = when (this) {
        OcrSkipReason.ModelsMissing -> R.string.scan_ocr_reason_models
        OcrSkipReason.LanguagePackUnavailable -> R.string.scan_ocr_reason_language_pack
        OcrSkipReason.RuntimeUnavailable -> R.string.scan_ocr_reason_runtime
        OcrSkipReason.UnsupportedFormat -> R.string.scan_ocr_reason_format
        OcrSkipReason.ImageTooLarge -> R.string.scan_ocr_reason_large
    }

    private fun OcrError.messageStringRes(): Int = when (this) {
        OcrError.InvalidSource -> R.string.scan_ocr_reason_invalid
        OcrError.InferenceFailed -> R.string.scan_ocr_reason_inference
    }

    private fun SearchablePdfExportFailure.messageStringRes(): Int = when (this) {
        SearchablePdfExportFailure.MissingOcrLayout -> R.string.searchable_pdf_error_missing_ocr
        SearchablePdfExportFailure.MissingFont -> R.string.searchable_pdf_error_missing_font
        SearchablePdfExportFailure.EmptyTextLayer -> R.string.searchable_pdf_error_empty_text_layer
    }

    private fun ScanProgress.toJobUpdate(): Pair<Int, String> = when (stage) {
        ScanProgressStage.Preparing -> 35 to text(R.string.event_scan_working)
        ScanProgressStage.Downloading -> {
            val total = totalPages?.coerceAtLeast(1) ?: 1
            val completed = completedPages.coerceIn(0, total)
            val progress = 40 + (completed * 30 / total)
            progress to text(R.string.event_scan_downloading, completed, total)
        }
        ScanProgressStage.Processing -> {
            val total = totalPages?.coerceAtLeast(1) ?: 1
            val completed = completedPages.coerceIn(0, total)
            val progress = 70 + (completed * 5 / total)
            progress to text(R.string.event_scan_processing, completed, total)
        }
        ScanProgressStage.Enhancing -> {
            val total = totalPages?.coerceAtLeast(1) ?: 1
            val completed = completedPages.coerceIn(0, total)
            val progress = 75 + (completed * 10 / total)
            progress to text(R.string.event_scan_enhancing, completed, total)
        }
        ScanProgressStage.Ocr -> {
            val total = totalPages?.coerceAtLeast(1) ?: 1
            val completed = completedPages.coerceIn(0, total)
            val progress = 85 + (completed * 10 / total)
            progress to text(R.string.event_scan_ocr, completed, total)
        }
    }

    private suspend fun fetchScannerCapabilities(
        provider: ScanAcquisitionProvider,
        scanner: IntegrationDevice,
    ): ScannerCapabilities? = try {
        provider.scannerCapabilities(scanner)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun notifyEnhancementDisabled(requested: ScanSettings, adjusted: ScanSettings) {
        if (requested.enhanceBackground != null && adjusted.enhanceBackground == null) {
            _events.tryEmit(text(R.string.event_scan_enhancement_disabled_high_resolution, adjusted.resolutionDpi))
        }
    }

    private fun notifyOcrDisabled(requested: ScanSettings, adjusted: ScanSettings) {
        if (requested.ocrMode != OcrMode.Disabled &&
            adjusted.ocrMode == OcrMode.Disabled
        ) {
            _events.tryEmit(text(R.string.event_scan_ocr_disabled_high_resolution, adjusted.resolutionDpi))
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
            } catch (error: SearchablePdfExportException) {
                _events.tryEmit(text(error.failure.messageStringRes()))
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
                persistDocuments()
                _events.tryEmit(text(R.string.event_export_location, text(format.labelRes)))
            } catch (error: CancellationException) {
                updateJob(jobId, JobStatus.Cancelled, 0, text(R.string.event_export_cancelled, text(format.labelRes)))
                _uiState.update { it.copy(activeJobId = null) }
                throw error
            } catch (error: SearchablePdfExportException) {
                val message = text(error.failure.messageStringRes())
                _uiState.update { it.copy(activeJobId = null) }
                updateJob(jobId, JobStatus.Failed, 0, message)
                _events.tryEmit(message)
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


    private fun persistDocuments() {
        val generation = documentPersistenceGeneration.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            documentStoreReady.await()
            documentPersistenceMutex.withLock {
                // Collapse queued writes and always snapshot state immediately before the newest save.
                if (generation != documentPersistenceGeneration.get()) return@withLock
                val state = _uiState.value
                runCatching { documentStore.save(state.documents, state.pendingFlatbedDocumentId) }
            }
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
        const val MAX_SCAN_PAGES = 50
        const val MIN_SCAN_PAGES = 1
    }
}
