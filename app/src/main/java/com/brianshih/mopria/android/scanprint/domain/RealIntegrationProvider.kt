package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.FileOutputStream
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
        ServiceType("_ipp._tcp.", DeviceKind.Printer, "IPP", secure = false),
        ServiceType("_ipps._tcp.", DeviceKind.Printer, "IPP / IPPS", secure = true),
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

            fun buildDevice(
                identity: String,
                service: ServiceType,
                info: NsdServiceInfo,
                host: String,
                resourcePath: String,
                uuid: String?,
                version: String?,
                displayName: String?,
                formats: List<String>,
            ) = IntegrationDevice(
                id = "real-${identity.hashCode()}",
                name = displayName ?: info.serviceName,
                kind = service.kind,
                protocol = version?.let { "${service.protocol} $it" } ?: service.protocol,
                isMock = false,
                host = host,
                port = info.port,
                secure = service.secure,
                serviceType = info.serviceType,
                resourcePath = resourcePath,
                uuid = uuid,
                esclVersion = version,
                advertisedFormats = formats,
            )

            fun addResolvedDevice(service: ServiceType, info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                if (service.kind == DeviceKind.Printer) {
                    val metadata = IppDiscovery.parse(info.attributes) ?: return
                    val identity = IppDiscovery.identity(info.serviceName, metadata)
                    val key = "${service.kind}:$identity"
                    devices[key] = IppDiscovery.preferred(devices[key], buildDevice(identity, service, info, host, metadata.resourcePath, metadata.uuid, metadata.version, metadata.displayName, metadata.documentFormats))
                } else {
                    val metadata = EsclDiscovery.parse(info.attributes) ?: return
                    val identity = EsclDiscovery.identity(info.serviceName, metadata)
                    val key = "${service.kind}:$identity"
                    devices[key] = EsclDiscovery.preferred(devices[key], buildDevice(identity, service, info, host, metadata.resourcePath, metadata.uuid, metadata.version, metadata.displayName, metadata.documentFormats))
                }
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

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument) = withContext(Dispatchers.IO) {
        val host = requireNotNull(printer.host) { "印表機缺少 host" }
        val uri = IppDiscovery.printerUri(host, printer.port ?: IppDiscovery.DEFAULT_PORT, printer.resourcePath, printer.secure)
        val client = IppPrintClient(BoundedIppTransport())
        val format = client.selectProducibleFormat(uri)
            ?: error("IPP 印表機不支援本 App 可產生的格式（PDF／JPEG／PNG）；printer pdl=${printer.advertisedFormats}")
        val rendered = renderPrintDocument(document, format)
        try {
            val submission = client.send(uri, rendered)
            client.awaitJobCompletion(uri, submission.jobId)
        } finally {
            rendered.delete()
        }
        Unit
    }

    /**
     * Renders a [MopriaDocument] into the negotiated IPP [format] for submission.
     *
     * PDF → one multi-page file via Android `PdfDocument`. JPEG/PNG → one compressed image per page;
     * each page becomes a separate Send-Document (see [IppPrintClient.send]). A page that cannot be
     * decoded fails loudly rather than producing a blank sheet. TODO(P2.7): share sampling logic with
     * `DocumentPageBitmapLoader` to bound memory for high-dpi scans.
     */
    private fun renderPrintDocument(document: MopriaDocument, format: String): RenderedPrintDocument = when (format) {
        IppDocumentFormat.PDF -> RenderedPrintDocument(format, listOf(renderDocumentPdf(document)))
        IppDocumentFormat.JPEG, IppDocumentFormat.PNG -> {
            val extension = if (format == IppDocumentFormat.JPEG) "jpg" else "png"
            val pages = document.pages.mapIndexed { index, page ->
                renderPageImage(document.id, index, page, format, extension)
            }
            RenderedPrintDocument(format, pages)
        }
        else -> error("無法渲染為 $format；僅支援 PDF／JPEG／PNG")
    }

    private fun renderPageImage(
        documentId: String,
        index: Int,
        page: DocumentPage,
        format: String,
        extension: String,
    ): File {
        val source = decodePageBitmap(page) ?: error("無法解碼頁面 ${index + 1}，無法產生 $format")
        val output = createBitmap(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            drawFittedBitmap(canvas, source)
            val compressFormat = if (format == IppDocumentFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val file = File(appContext.cacheDir, "ipp-print-$documentId-page-${index + 1}.$extension")
            FileOutputStream(file).use { out -> output.compress(compressFormat, IPP_IMAGE_QUALITY, out) }
            return file
        } finally {
            source.recycle()
            output.recycle()
        }
    }

    /**
     * Renders a [MopriaDocument] to a single multi-page PDF in the cache dir for IPP submission.
     * TODO: consolidate with `ScanExportService.drawPage`/`writePdf` into a shared renderer.
     */
    private fun renderDocumentPdf(document: MopriaDocument): File {
        val output = File(appContext.cacheDir, "ipp-print-${document.id}.pdf")
        val pdf = PdfDocument()
        try {
            document.pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, index + 1).create()
                val pdfPage = pdf.startPage(info)
                drawPrintPage(pdfPage.canvas, page)
                pdf.finishPage(pdfPage)
            }
            FileOutputStream(output).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return output
    }

    private fun drawPrintPage(canvas: Canvas, page: DocumentPage) {
        canvas.drawColor(Color.WHITE)
        val bitmap = decodePageBitmap(page) ?: return
        try {
            drawFittedBitmap(canvas, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Draws [bitmap] fitted within [PDF_MARGIN] on [canvas], preserving aspect ratio. */
    private fun drawFittedBitmap(canvas: Canvas, bitmap: Bitmap) {
        val scale = minOf(
            (canvas.width - PDF_MARGIN * 2) / bitmap.width.toFloat(),
            (canvas.height - PDF_MARGIN * 2) / bitmap.height.toFloat(),
        ).coerceAtMost(MAX_BITMAP_SCALE)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (canvas.width - width) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, PDF_MARGIN.toFloat(), left + width, PDF_MARGIN + height), null)
    }

    private fun decodePageBitmap(page: DocumentPage): Bitmap? {
        page.imagePath?.let { return decodeImage(it) }
        val pdfPath = page.pdfPath ?: return null
        return renderPdfPage(pdfPath, page.pdfPageIndex ?: 0)
    }

    private fun decodeImage(path: String): Bitmap? {
        val file = File(path.removePrefix("file://"))
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun renderPdfPage(path: String, pageIndex: Int): Bitmap? {
        val file = File(path.removePrefix("file://"))
        if (!file.isFile) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(PDF_PAGE_WIDTH.toFloat() / page.width, PDF_PAGE_HEIGHT.toFloat() / page.height)
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        createBitmap(width, height).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }.getOrNull()
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
        const val PDF_PAGE_WIDTH = 612 // US Letter, points
        const val PDF_PAGE_HEIGHT = 792
        const val PDF_MARGIN = 36
        const val MAX_BITMAP_SCALE = 1f
        const val IPP_IMAGE_QUALITY = 90
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
