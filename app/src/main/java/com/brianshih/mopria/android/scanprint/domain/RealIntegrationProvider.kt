package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Real network discovery plus the eSCL scan workflow. */
class RealIntegrationProvider(context: Context) : DeviceDiscovery, ScanAcquisitionProvider, PrintProvider {
    private val appContext = context.applicationContext
    private val nsdManager = checkNotNull(appContext.getSystemService(NsdManager::class.java))
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = EsclHttpClient()

    private val serviceTypes = listOf(
        ServiceType("_uscan._tcp.", DeviceKind.Scanner, "eSCL / AirScan", secure = false),
        ServiceType("_uscans._tcp.", DeviceKind.Scanner, "eSCL / AirScan TLS", secure = true),
    )

    @Suppress("DEPRECATION")
    override suspend fun discover(): List<IntegrationDevice> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val devices = linkedMapOf<String, IntegrationDevice>()
            val startedListeners = mutableSetOf<NsdManager.DiscoveryListener>()
            val resolutionQueue = ArrayDeque<PendingResolution>()
            val queuedKeys = mutableSetOf<String>()
            var resolving = false
            var finished = false
            lateinit var timeout: Runnable

            fun stopDiscovery() {
                startedListeners.toList().forEach { listener ->
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }
                startedListeners.clear()
            }

            fun finish() {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeout)
                stopDiscovery()
                if (continuation.isActive) continuation.resume(devices.values.toList())
            }

            fun addResolvedDevice(service: ServiceType, info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val metadata = EsclDiscovery.parse(info.attributes) ?: return
                val identity = EsclDiscovery.identity(info.serviceName, metadata)
                val key = "${service.kind}:$identity"
                val candidate = IntegrationDevice(
                    id = "real-${identity.hashCode()}",
                    name = metadata.displayName ?: info.serviceName,
                    kind = service.kind,
                    protocol = metadata.version?.let { "${service.protocol} $it" } ?: service.protocol,
                    isMock = false,
                    host = host,
                    port = info.port,
                    secure = service.secure,
                    serviceType = info.serviceType,
                    resourcePath = metadata.resourcePath,
                    uuid = metadata.uuid,
                    esclVersion = metadata.version,
                    advertisedFormats = metadata.documentFormats,
                )
                devices[key] = EsclDiscovery.preferred(devices[key], candidate)
            }

            fun resolveNext() {
                if (finished || resolving || resolutionQueue.isEmpty()) return
                val pending = resolutionQueue.removeFirst()
                resolving = true
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        addResolvedDevice(pending.service, serviceInfo)
                        resolveNext()
                    }

                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        resolveNext()
                    }
                }
                runCatching { nsdManager.resolveService(pending.info, resolveListener) }
                    .onFailure {
                        resolving = false
                        resolveNext()
                    }
            }

            timeout = Runnable { finish() }
            continuation.invokeOnCancellation {
                finished = true
                mainHandler.removeCallbacks(timeout)
                stopDiscovery()
            }

            serviceTypes.forEach { service ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        startedListeners += this
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val key = "${service.kind}:${serviceInfo.serviceName}:${serviceInfo.serviceType}"
                        if (queuedKeys.add(key)) {
                            resolutionQueue.add(PendingResolution(service, serviceInfo))
                            resolveNext()
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        startedListeners.remove(this)
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        startedListeners.remove(this)
                        runCatching { nsdManager.stopServiceDiscovery(this) }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        startedListeners.remove(this)
                    }
                }
                startedListeners += listener
                runCatching { nsdManager.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, listener) }
                    .onFailure { startedListeners.remove(listener) }
            }
            mainHandler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        }
    }

    override suspend fun scan(scanner: IntegrationDevice, settings: ScanSettings): MopriaDocument = withContext(Dispatchers.IO) {
        val baseUrl = scanner.eSclBaseUrl()
        validateScannerReady(httpClient.fetchScannerStatus(baseUrl), settings.inputSource)
        val capabilitiesXml = httpClient.fetchCapabilities(baseUrl)
        val capabilities = EsclProtocol.parseCapabilities(capabilitiesXml)
        val negotiated = EsclProtocol.negotiate(capabilities, settings)
        val scanSettingsXml = EsclProtocol.buildScanSettings(negotiated)
        val scanId = System.currentTimeMillis()
        val directory = File(appContext.filesDir, "scans").apply { mkdirs() }
        val payloads = mutableListOf<EsclDocumentPayload>()
        var location: String? = null
        var jobFinished = false
        try {
            val jobLocation = httpClient.createScanJob(baseUrl, scanSettingsXml)
            location = jobLocation
            val jobUrl = EsclProtocol.scanJobUrl(baseUrl, jobLocation)
            awaitJobReady(baseUrl, jobUrl)
            var reachedEnd = false
            val pageLimit = if (settings.inputSource == ScanInputSource.Flatbed) 1 else settings.maxPages.coerceIn(1, MAX_SCAN_PAGES)
            pageLoop@
            for (index in 0 until pageLimit) {
                currentCoroutineContext().ensureActive()
                val temporaryFile = File(directory, "real-scan-$scanId-page-${index + 1}.part")
                var interruptionRetries = 0
                val payload: EsclDocumentPayload
                while (true) {
                    val fetched = try {
                        httpClient.fetchNextDocument(
                            EsclProtocol.nextDocumentUrl(baseUrl, jobLocation),
                            temporaryFile,
                        )
                    } catch (error: EsclNextDocumentTimeoutException) {
                        when (jobTransferState(baseUrl, jobUrl)) {
                            JobTransferState.Completed -> {
                                reachedEnd = true
                                break@pageLoop
                            }
                            JobTransferState.Active -> {
                                if (interruptionRetries >= NEXT_DOCUMENT_STATUS_RETRIES) throw error
                                interruptionRetries += 1
                                continue
                            }
                            JobTransferState.Missing -> throw error
                        }
                    } catch (error: EsclHttpException) {
                        if (error.statusCode == HTTP_GONE && jobTransferState(baseUrl, jobUrl) == JobTransferState.Completed) {
                            reachedEnd = true
                            break@pageLoop
                        }
                        throw error
                    }
                    if (fetched == null) {
                        reachedEnd = true
                        break@pageLoop
                    }
                    payload = fetched
                    break
                }
                val extension = when {
                    payload.contentType.contains("png") -> "png"
                    payload.contentType.contains("pdf") -> "pdf"
                    else -> "jpg"
                }
                val finalFile = File(directory, "real-scan-$scanId-page-${index + 1}.$extension")
                if (!payload.file.renameTo(finalFile)) {
                    payload.file.delete()
                    error("Could not save scanned page ${index + 1}")
                }
                payloads += payload.copy(file = finalFile)
            }

            if (reachedEnd || settings.inputSource == ScanInputSource.Flatbed || negotiated.numberOfPages != null) {
                awaitJobCompletion(baseUrl, jobUrl)
            } else {
                httpClient.cancelScanJob(baseUrl, jobLocation)
            }
            jobFinished = true

            require(payloads.isNotEmpty()) { "eSCL ScanJob 沒有回傳影像" }
            val pages = payloads.flatMapIndexed { payloadIndex, payload ->
                if (payload.contentType == "application/pdf") {
                    val count = pdfPageCount(payload.file)
                    (0 until count).map { pdfPageIndex ->
                        DocumentPage(
                            id = "$scanId-pdf-$payloadIndex-page-${pdfPageIndex + 1}",
                            pageNumber = 0,
                            title = "",
                            pdfPath = payload.file.absolutePath,
                            pdfPageIndex = pdfPageIndex,
                        )
                    }
                } else {
                    listOf(
                        DocumentPage(
                            id = "$scanId-image-$payloadIndex",
                            pageNumber = 0,
                            title = "",
                            imagePath = payload.file.absolutePath,
                        ),
                    )
                }
            }
                .take(pageLimit)
                .mapIndexed { index, page -> page.copy(pageNumber = index + 1, title = "Scanned page ${index + 1}") }

            MopriaDocument(
                id = "real-scan-$scanId",
                name = "Scanned document ${scanId.toString().takeLast(4)}",
                sourceLabel = "${scanner.name} · eSCL · ${settings.inputSource.eSclValue} · ${resolutionLabel(negotiated)} · ${colorModeLabel(negotiated.colorMode)}",
                pages = pages,
                createdAt = scanId,
            )
        } catch (error: Exception) {
            if (!jobFinished && location != null) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { httpClient.cancelScanJob(baseUrl, location) } }
            }
            payloads.map(EsclDocumentPayload::file).forEach(File::delete)
            throw error
        }
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument) {
        error("Android Print Framework 必須由 Activity 開啟系統列印預覽")
    }

    private fun IntegrationDevice.eSclBaseUrl(): String {
        val resolvedHost = requireNotNull(host) { "掃描器缺少 resolved host" }
            .let { value ->
                val encoded = value.replace("%", "%25")
                if (encoded.contains(":") && !encoded.startsWith("[") && !encoded.endsWith("]")) "[$encoded]" else encoded
            }
        val resolvedPort = port ?: if (secure) 443 else 80
        val scheme = if (secure) "https" else "http"
        return "$scheme://$resolvedHost:$resolvedPort/${resourcePath.trim('/')}"
    }

    private fun validateScannerReady(status: EsclScannerStatus, source: ScanInputSource) {
        require(status.state.equals("Idle", true)) {
            val description = when {
                status.state.equals("Processing", true) -> "掃描器忙碌中"
                status.state.equals("Testing", true) -> "掃描器正在校正"
                status.state.equals("Stopped", true) -> "掃描器已停止，請檢查設備"
                status.state.equals("Down", true) -> "掃描器目前無法使用"
                else -> "掃描器目前狀態為 ${status.state}"
            }
            description
        }
        if (source == ScanInputSource.Adf) {
            val adfState = status.adfState ?: return
            require(adfState.equals("ScannerAdfLoaded", true) || adfState.equals("ScannerAdfProcessing", true)) {
                if (adfState.equals("ScannerAdfEmpty", true)) "ADF 尚未放入文件" else "ADF 狀態異常：$adfState"
            }
        }
    }

    private suspend fun awaitJobCompletion(baseUrl: String, jobUrl: String) {
        repeat(JOB_STATUS_ATTEMPTS) {
            val job = httpClient.fetchScannerStatus(baseUrl).jobFor(jobUrl)
            when {
                job == null -> return
                job.state.equals("Completed", true) -> return
                job.state.equals("Canceled", true) || job.state.equals("Aborted", true) -> {
                    error("eSCL ScanJob ${job.state}：${job.stateReasons.joinToString().ifBlank { "unknown reason" }}")
                }
            }
            delay(JOB_STATUS_POLL_MS)
        }
        error("等待 eSCL ScanJob 完成逾時")
    }

    private suspend fun awaitJobReady(baseUrl: String, jobUrl: String) {
        repeat(JOB_READY_ATTEMPTS) {
            val job = httpClient.fetchScannerStatus(baseUrl).jobFor(jobUrl)
            when {
                job == null -> Unit
                job.state.equals("Pending", true) || job.state.equals("Processing", true) || job.state.equals("Completed", true) -> return
                job.state.equals("Canceled", true) || job.state.equals("Aborted", true) -> {
                    error("eSCL ScanJob ${job.state}：${job.stateReasons.joinToString().ifBlank { "unknown reason" }}")
                }
            }
            delay(JOB_STATUS_POLL_MS)
        }
        error("等待 eSCL ScanJob 可傳輸逾時")
    }

    private fun jobTransferState(baseUrl: String, jobUrl: String): JobTransferState {
        val job = httpClient.fetchScannerStatus(baseUrl).jobFor(jobUrl) ?: return JobTransferState.Missing
        return when {
            job.state.equals("Pending", true) || job.state.equals("Processing", true) -> JobTransferState.Active
            job.state.equals("Completed", true) -> JobTransferState.Completed
            job.state.equals("Canceled", true) || job.state.equals("Aborted", true) -> {
                error("eSCL ScanJob ${job.state}：${job.stateReasons.joinToString().ifBlank { "unknown reason" }}")
            }
            else -> JobTransferState.Missing
        }
    }

    private fun pdfPageCount(file: File): Int {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        }.also { require(it > 0) { "掃描器回傳的 PDF 沒有頁面" } }
    }

    private data class ServiceType(
        val type: String,
        val kind: DeviceKind,
        val protocol: String,
        val secure: Boolean,
    )

    private data class PendingResolution(
        val service: ServiceType,
        val info: NsdServiceInfo,
    )

    private enum class JobTransferState { Active, Completed, Missing }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 8_000L
        const val MAX_SCAN_PAGES = 50
        const val JOB_READY_ATTEMPTS = 60
        const val JOB_STATUS_ATTEMPTS = 120
        const val JOB_STATUS_POLL_MS = 500L
        const val NEXT_DOCUMENT_STATUS_RETRIES = 3
        const val HTTP_GONE = 410

        fun colorModeLabel(value: String): String = ScanColorMode.entries
            .firstOrNull { it.eSclValue.equals(value, ignoreCase = true) }
            ?.eSclValue
            ?: value

        fun resolutionLabel(settings: EsclNegotiatedSettings): String = if (settings.resolution == settings.yResolution) {
            "${settings.resolution} dpi"
        } else {
            "${settings.resolution}×${settings.yResolution} dpi"
        }
    }
}
