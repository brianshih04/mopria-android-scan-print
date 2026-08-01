package com.brianshih.mopria.android.scanprint.ui

import android.app.Application
import android.content.Context
import android.print.PrintJob
import android.print.PrintJobInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.brianshih.mopria.android.scanprint.domain.ScanOutputFormat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MopriaViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mockProvider = MockIntegrationProvider()
    private val realProvider = RealIntegrationProvider(application)
    private val _uiState = MutableStateFlow(
        MopriaUiState(integrationMode = loadIntegrationMode()),
    )
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val _printRequests = MutableSharedFlow<MopriaDocument>(extraBufferCapacity = 4)
    private val scanExportService = ScanExportService(application)

    val uiState = _uiState.asStateFlow()
    val events = _events.asSharedFlow()
    val printRequests = _printRequests.asSharedFlow()

    init {
        viewModelScope.launch {
            val savedDocuments = scanExportService.loadSavedDocuments()
            if (savedDocuments.isNotEmpty()) {
                _uiState.update { it.copy(documents = savedDocuments + it.documents) }
            }
        }
    }

    fun setIntegrationMode(mode: IntegrationMode) {
        if (_uiState.value.isBusy || _uiState.value.integrationMode == mode) return

        preferences.edit().putString(KEY_INTEGRATION_MODE, mode.name).apply()
        _uiState.update {
            it.copy(
                integrationMode = mode,
                devices = emptyList(),
            )
        }
        _events.tryEmit("已切換至${mode.label}：${mode.description}")
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
            } catch (error: Exception) {
                _uiState.update { it.copy(isDiscovering = false, devices = emptyList()) }
                _events.tryEmit("${mode.label}搜尋失敗：${error.message ?: "請稍後重試"}")
            }
        }
    }

    fun scan() {
        if (_uiState.value.isBusy) return

        viewModelScope.launch {
            val mode = _uiState.value.integrationMode
            var jobId: String? = null
            try {
                _uiState.update { it.copy(isDiscovering = true) }
                val providers = providersFor(mode)
                val devices = providers.discovery.discover()
                val scanner = devices.firstOrNull { it.kind == DeviceKind.Scanner }
                if (scanner == null) {
                    _uiState.update { it.copy(isDiscovering = false, devices = devices) }
                    _events.tryEmit("${mode.label}沒有找到掃描器，請確認設備與手機在同一個區域網路")
                    return@launch
                }

                val currentJobId = "scan-job-${System.currentTimeMillis()}"
                jobId = currentJobId
                val queuedJob = JobRecord(
                    id = currentJobId,
                    kind = JobKind.Scan,
                    title = "掃描文件",
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
                updateJob(currentJobId, JobStatus.Running, 35, "正在建立掃描工作")
                val document = providers.scan.scan(scanner)
                updateJob(currentJobId, JobStatus.Running, 85, "正在整理頁面")
                _uiState.update {
                    it.copy(
                        activeJobId = null,
                        selectedDocumentId = document.id,
                        documents = listOf(document) + it.documents,
                    )
                }
                updateJob(currentJobId, JobStatus.Completed, 100, "已取得 ${document.pages.size} 頁")
                _events.tryEmit("${mode.label}掃描完成：${document.pages.size} 頁已加入文件庫")
            } catch (error: Exception) {
                jobId?.let { updateJob(it, JobStatus.Failed, 0, error.message ?: "掃描失敗") }
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                _events.tryEmit("${mode.label}掃描失敗：${error.message ?: "請稍後重試"}")
            }
        }
    }

    fun createDemoDocument() {
        val document = demoDocument()
        _uiState.update {
            it.copy(
                selectedDocumentId = document.id,
                documents = listOf(document) + it.documents,
            )
        }
        _events.tryEmit("已建立示範文件，可直接測試匯出與列印")
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
                val devices = providers.discovery.discover()
                val printer = devices.firstOrNull { it.kind == DeviceKind.Printer }
                if (printer == null) {
                    _uiState.update { it.copy(isDiscovering = false, devices = devices) }
                    _events.tryEmit("${mode.label}沒有找到印表機，請確認設備與手機在同一個區域網路")
                    return@launch
                }

                if (mode == IntegrationMode.Real) {
                    _uiState.update {
                        it.copy(
                            isDiscovering = false,
                            devices = devices,
                            selectedDocumentId = document.id,
                        )
                    }
                    _printRequests.tryEmit(document)
                    _events.tryEmit("已找到 ${printer.name}，正在開啟 Android 系統列印預覽")
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
                updateJob(currentJobId, JobStatus.Running, 45, "正在交給 Android Print Framework")
                providers.print.print(printer, document)
                updateJob(currentJobId, JobStatus.Completed, 100, "列印工作已完成")
                _uiState.update { it.copy(activeJobId = null) }
                _events.tryEmit("${mode.label}列印完成：${document.name}")
            } catch (error: Exception) {
                jobId?.let { updateJob(it, JobStatus.Failed, 0, error.message ?: "列印失敗") }
                _uiState.update { it.copy(isDiscovering = false, activeJobId = null) }
                _events.tryEmit("${mode.label}列印失敗：${error.message ?: "請稍後重試"}")
            }
        }
    }

    fun export(documentId: String? = null) {
        saveScan(documentId, ScanOutputFormat.Pdf)
    }

    fun saveJpegs(documentId: String? = null) {
        saveScan(documentId, ScanOutputFormat.Jpeg)
    }

    private fun saveScan(documentId: String?, format: ScanOutputFormat) {
        if (_uiState.value.isBusy) return

        val document = documentId?.let { id -> _uiState.value.documents.firstOrNull { it.id == id } }
            ?: _uiState.value.documents.firstOrNull()
        if (document == null) {
            _events.tryEmit("請先建立或掃描一份文件")
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

            runCatching { scanExportService.save(document, format) }
                .onSuccess { file ->
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
                    updateJob(jobId, JobStatus.Completed, 100, "已儲存 ${file.size} 個 ${format.label} 檔案")
                    _events.tryEmit("${format.label} 已儲存到 Download/Mopria Scan & Print/Scans")
                }
                .onFailure { error ->
                    _uiState.update { it.copy(activeJobId = null) }
                    updateJob(jobId, JobStatus.Failed, 0, error.message ?: "${format.label} 輸出失敗")
                    _events.tryEmit("${format.label} 輸出失敗，請重試")
                }
        }
    }

    fun recordSystemPrint(fileCount: Int) {
        val job = JobRecord(
            id = "system-print-${System.currentTimeMillis()}",
            kind = JobKind.Print,
            title = if (fileCount == 1) "手機文件" else "手機文件（${fileCount} 個）",
            targetLabel = "Android Print Framework",
            status = JobStatus.Queued,
            progress = 0,
            detail = "已開啟系統列印預覽，等待使用者確認",
        )
        _uiState.update { it.copy(jobs = listOf(job) + it.jobs) }
        _events.tryEmit("已送至 Android 系統列印預覽")
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
            detail = "已開啟系統列印預覽",
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
                        PrintJobInfo.STATE_BLOCKED -> "列印服務暫停，等待恢復"
                        PrintJobInfo.STATE_STARTED -> "列印服務正在處理"
                        else -> "等待列印服務確認"
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
                JobStatus.Completed -> "系統列印工作已完成"
                JobStatus.Cancelled -> "系統列印工作已取消"
                else -> "系統列印工作失敗"
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
                activeJobId = if (status == JobStatus.Completed || status == JobStatus.Failed) null else it.activeJobId,
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

    private fun discoveryMessage(mode: IntegrationMode, devices: List<IntegrationDevice>): String = when {
        mode == IntegrationMode.Mock -> "找到 ${devices.size} 個模擬裝置，可開始測試流程"
        devices.isEmpty() -> "真實模式尚未找到設備，請確認 Wi-Fi 與設備服務已開啟"
        else -> "真實模式找到 ${devices.size} 個網路設備"
    }

    private fun demoDocument(): MopriaDocument {
        val timestamp = System.currentTimeMillis()
        return MopriaDocument(
            id = "demo-$timestamp",
            name = "示範文件 ${timestamp.toString().takeLast(4)}",
            sourceLabel = "本機 Mock Integration Mode",
            pages = listOf(
                DocumentPage("$timestamp-demo-1", 1, "示範首頁"),
                DocumentPage("$timestamp-demo-2", 2, "示範內容"),
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
    }
}
