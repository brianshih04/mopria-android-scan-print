package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.hp.jipp.pdl.ColorSpace
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
class RealIntegrationProvider(
    context: Context,
    ocrEngine: OcrEngine? = null,
) : DeviceDiscovery, ScanAcquisitionProvider, PrintProvider {
    private val appContext = context.applicationContext
    private val activeOcrEngine = ocrEngine ?: MlKitOcrEngine.production(appContext)
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
    override suspend fun discover(manualDeviceAddress: String?): List<IntegrationDevice> = withContext(Dispatchers.Main.immediate) {
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
                ManualDeviceAddress.candidates(manualDeviceAddress.orEmpty()).forEach { candidate ->
                    val sameHostKeys = devices
                        .filterValues { existing ->
                            existing.kind == candidate.kind && existing.host.equals(candidate.host, ignoreCase = true)
                        }
                        .keys
                        .toList()
                    // An explicit manual host is also the user's opt-in to the plain 80/631
                    // endpoints described in Settings. Do not let an untrusted mDNS TLS service
                    // shadow the reachable manual candidate on the same host.
                    val replacesSecureManualCandidate =
                        devices.values.any { existing ->
                            existing.kind == candidate.kind &&
                                existing.host.equals(candidate.host, ignoreCase = true) &&
                                existing.secure && !candidate.secure
                        }
                    if (sameHostKeys.isEmpty() || replacesSecureManualCandidate) {
                        sameHostKeys.forEach(devices::remove)
                        devices["manual:${candidate.kind}:${candidate.host}"] = candidate
                    }
                }
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

    suspend fun discover(): List<IntegrationDevice> = discover(null)

    override suspend fun scan(
        scanner: IntegrationDevice,
        settings: ScanSettings,
        onProgress: (ScanProgress) -> Unit,
    ): MopriaDocument = withContext(Dispatchers.IO) {
        onProgress(ScanProgress(ScanProgressStage.Preparing))
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
        var adfJamDetected = false
        try {
            val jobLocation = httpClient.createScanJob(baseUrl, scanSettingsXml)
            location = jobLocation
            val jobUrl = EsclProtocol.scanJobUrl(baseUrl, jobLocation)
            awaitJobReady(baseUrl, jobUrl)
            val pageLimit = if (settings.inputSource == ScanInputSource.Flatbed) 1 else settings.maxPages.coerceIn(1, MAX_SCAN_PAGES)
            onProgress(ScanProgress(ScanProgressStage.Downloading, 0, pageLimit))
            pageLoop@
            for (index in 0 until pageLimit) {
                currentCoroutineContext().ensureActive()
                if (index > 0 && settings.inputSource == ScanInputSource.Adf) {
                    delay(ADF_NEXT_DOCUMENT_DELAY_MS)
                }
                val temporaryFile = File(directory, "real-scan-$scanId-page-${index + 1}.part")
                var interruptionRetries = 0
                var notFoundRetries = 0
                val payload: EsclDocumentPayload
                while (true) {
                    val fetched = try {
                        httpClient.fetchNextDocument(
                            EsclProtocol.nextDocumentUrl(baseUrl, jobLocation),
                            temporaryFile,
                        )
                    } catch (error: EsclNextDocumentTimeoutException) {
                        val scannerStatus = runCatching { httpClient.fetchScannerStatus(baseUrl) }.getOrNull()
                        if (settings.inputSource == ScanInputSource.Adf && scannerStatus?.isAdfJam(jobUrl) == true) {
                            adfJamDetected = true
                            break@pageLoop
                        }
                        when (jobTransferState(baseUrl, jobUrl)) {
                            JobTransferState.Completed -> {
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
                        val scannerStatus = runCatching { httpClient.fetchScannerStatus(baseUrl) }.getOrNull()
                        if (settings.inputSource == ScanInputSource.Adf && scannerStatus?.isAdfJam(jobUrl) == true) {
                            adfJamDetected = true
                            break@pageLoop
                        }
                        if (error.statusCode == HTTP_GONE && jobTransferState(baseUrl, jobUrl) == JobTransferState.Completed) {
                            break@pageLoop
                        }
                        throw error
                    }
                    if (fetched == null) {
                        val scannerStatus = if (settings.inputSource == ScanInputSource.Adf) {
                            runCatching { httpClient.fetchScannerStatus(baseUrl) }.getOrNull()
                        } else {
                            null
                        }
                        if (scannerStatus?.isAdfJam(jobUrl) == true) {
                            adfJamDetected = true
                            break@pageLoop
                        }
                        val adfStillLoaded = scannerStatus?.adfState?.let { state ->
                            state.equals("ScannerAdfLoaded", true) || state.equals("ScannerAdfProcessing", true)
                        } == true
                        if (adfStillLoaded && notFoundRetries < NEXT_DOCUMENT_STATUS_RETRIES) {
                            notFoundRetries += 1
                            delay(ADF_NEXT_DOCUMENT_DELAY_MS)
                            continue
                        }
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
                onProgress(ScanProgress(ScanProgressStage.Downloading, index + 1, pageLimit))
            }

            // A number of MFPs, including the tested Brother firmware, keep the job in
            // Processing after the final image has been transferred and only release it
            // when the client deletes the job. Do not wait for a Completed state after
            // all requested payloads are already local.
            finishScanJob(baseUrl, jobUrl, jobLocation)
            jobFinished = true

            if (payloads.isEmpty()) {
                if (adfJamDetected) throw ScanError.AdfJam(null)
                throw ScanError.NoImages
            }

            val ocrProcessingSettings = settings.copy(
                deskew = settings.deskew || settings.ocrMode != OcrMode.Disabled,
                autoCrop = settings.autoCrop || settings.ocrMode != OcrMode.Disabled,
            )
            val imageProcessingResults = if (
                ocrProcessingSettings.deskew ||
                ocrProcessingSettings.autoCrop ||
                ocrProcessingSettings.dropBlankPages
            ) {
                onProgress(ScanProgress(ScanProgressStage.Processing, 0, payloads.size))
                val downloaded = payloads.toList()
                val results = mutableListOf<ScanImageResult>()
                val retained = mutableListOf<EsclDocumentPayload>()
                downloaded.forEachIndexed { index, payload ->
                    val result = ScanImagePipeline.process(payload.file, ocrProcessingSettings)
                    results += result
                    if (result != ScanImageResult.DroppedBlankPage) {
                        retained += payload
                    } else {
                        payload.file.delete()
                    }
                    onProgress(ScanProgress(ScanProgressStage.Processing, index + 1, downloaded.size))
                }
                payloads.clear()
                payloads += retained
                results
            } else {
                emptyList()
            }

            if (payloads.isEmpty()) {
                if (adfJamDetected) throw ScanError.AdfJam(null)
                throw ScanError.NoImages
            }

            // Apply background enhancement after download. The result is carried with the
            // document so the ViewModel can report Skipped/Failed without losing the source.
            val enhancementResults = settings.enhanceBackground?.let { strength ->
                onProgress(ScanProgress(ScanProgressStage.Enhancing, 0, payloads.size))
                payloads.mapIndexed { index, payload ->
                    BackgroundEnhancer.enhanceImageFile(payload.file, strength).also {
                        onProgress(ScanProgress(ScanProgressStage.Enhancing, index + 1, payloads.size))
                    }
                }
            }.orEmpty()

            val ocrResults = if (settings.ocrMode != OcrMode.Disabled) {
                onProgress(ScanProgress(ScanProgressStage.Ocr, 0, payloads.size))
                payloads.mapIndexed { index, payload ->
                    val mediaType = payload.contentType.substringBefore(';').trim().lowercase()
                    val result = if (mediaType == "application/pdf") {
                        // A non-conforming scanner may ignore the requested JPEG format. Never
                        // pass a PDF to ML Kit's raster-only InputImage decoder.
                        OcrResult.Skipped(OcrSkipReason.UnsupportedFormat)
                    } else if (activeOcrEngine is LanguageAwareOcrEngine) {
                        activeOcrEngine.recognize(payload.file, settings.ocrLanguage)
                    } else {
                        activeOcrEngine.recognize(payload.file)
                    }
                    result.also {
                        onProgress(ScanProgress(ScanProgressStage.Ocr, index + 1, payloads.size))
                    }
                }
            } else {
                emptyList()
            }

            val pages = payloads.flatMapIndexed { payloadIndex, payload ->
                val ocrResult = ocrResults.getOrNull(payloadIndex)
                val mediaType = payload.contentType.substringBefore(';').trim()
                if (mediaType.equals("application/pdf", ignoreCase = true)) {
                    val count = pdfPageCount(payload.file)
                    (0 until count).map { pdfPageIndex ->
                        DocumentPage(
                            id = "$scanId-pdf-$payloadIndex-page-${pdfPageIndex + 1}",
                            pageNumber = 0,
                            title = "",
                            pdfPath = payload.file.absolutePath,
                            pdfPageIndex = pdfPageIndex,
                            // The current OCR engine accepts image files only. Keep PDF-backed
                            // pages image-only until they are rasterized for page-scoped OCR.
                            ocrResult = null,
                        )
                    }
                } else {
                    listOf(
                        DocumentPage(
                            id = "$scanId-image-$payloadIndex",
                            pageNumber = 0,
                            title = "",
                            imagePath = payload.file.absolutePath,
                            ocrResult = ocrResult,
                        ),
                    )
                }
            }
                .take(pageLimit)
                .mapIndexed { index, page ->
                    page.copy(
                        pageNumber = index + 1,
                        title = "Scanned page ${index + 1}",
                        generatedTitle = GeneratedPageTitle.Scanned,
                        generatedTitleNumber = index + 1,
                    )
                }

            val document = MopriaDocument(
                id = "real-scan-$scanId",
                name = "Scanned document ${scanId.toString().takeLast(4)}",
                sourceLabel = "${scanner.name} · eSCL · ${settings.inputSource.displayToken} · ${resolutionLabel(negotiated)} · ${colorModeLabel(negotiated.colorMode)}",
                pages = pages,
                createdAt = scanId,
                enhancementResults = enhancementResults,
                imageProcessingResults = imageProcessingResults,
                ocrResults = ocrResults,
                // Preserve the user's explicit request. Export validates that every page has an
                // OCR result and reports an actionable error instead of silently writing a plain PDF.
                searchablePdf = settings.searchablePdf && settings.ocrMode != OcrMode.Disabled,
                documentSize = settings.documentSize,
                actualScanSettingsReported = false,
                generatedName = GeneratedDocumentName.Scanned,
                generatedNameSuffix = scanId.toString().takeLast(4),
            )
            if (adfJamDetected) throw ScanError.AdfJam(document)
            document
        } catch (error: ScanError.AdfJam) {
            // Keep the downloaded page files: the ViewModel will attach this partial document to
            // the pending ADF session and append the next job's pages after the user clears the jam.
            if (location != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { httpClient.cancelScanJob(baseUrl, location) }
                }
            }
            directory.listFiles()
                ?.filter { file -> file.name.startsWith("real-scan-$scanId-page-") && file.extension == "part" }
                ?.forEach(File::delete)
            throw error
        } catch (error: EsclHttpException) {
            if (!jobFinished && location != null) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { httpClient.cancelScanJob(baseUrl, location) } }
            }
            payloads.map(EsclDocumentPayload::file).forEach(File::delete)
            throw ScanError.HttpError(error.statusCode)
        } catch (error: EsclNextDocumentTimeoutException) {
            if (!jobFinished && location != null) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { httpClient.cancelScanJob(baseUrl, location) } }
            }
            payloads.map(EsclDocumentPayload::file).forEach(File::delete)
            throw ScanError.DocumentTimeout
        } catch (error: Exception) {
            if (!jobFinished && location != null) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { httpClient.cancelScanJob(baseUrl, location) } }
            }
            payloads.map(EsclDocumentPayload::file).forEach(File::delete)
            throw error
        }
    }

    
    override suspend fun scannerCapabilities(scanner: IntegrationDevice): ScannerCapabilities? = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = scanner.eSclBaseUrl()
            val capabilitiesXml = httpClient.fetchCapabilities(baseUrl)
            val capabilities = EsclProtocol.parseCapabilities(capabilitiesXml)
            ScannerCapabilities.fromEscl(capabilities)
        }.getOrNull()
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument, options: PrintOptions?) = withContext(Dispatchers.IO) {
        val host = requireNotNull(printer.host) { "printer missing resolved host" }
        val uri = IppDiscovery.printerUri(host, printer.port ?: IppDiscovery.DEFAULT_PORT, printer.resourcePath, printer.secure)
        val client = IppPrintClient(BoundedIppTransport())
        // One Get-Printer-Attributes drives both format negotiation and capability coercion, so a print
        // never sends a job-template attribute the printer does not advertise.
        val attributes = client.getPrinterAttributes(uri)
        val format = IppDocumentFormat.select(
            client.documentFormatsSupported(attributes),
            IppDocumentFormat.producible,
        ) ?: throw PrintError.UnsupportedFormat(printer.advertisedFormats)
        val dpi = client.preferredResolution(attributes)
        val pclmStripHeight = client.pclmStripHeightPreferred(attributes)
        val supportsMultipleDocuments = client.multipleDocumentJobsSupported(attributes)
        val capabilities = client.parseCapabilities(attributes)
        val preferredMedia = (document.documentSize ?: ScanDocumentSize.A4).toIppMediaKeyword()
        val effectiveOptions = (options ?: PrintOptions()).let { selected ->
            selected.copy(media = selected.media ?: preferredMedia)
        }
        val coercedOptions = effectiveOptions.coerceTo(capabilities)
        val colorSpace = chooseColorSpace(client.printColorModesSupported(attributes), coercedOptions?.colorMode)
        val rendered = renderPrintDocument(document, format, dpi, colorSpace, pclmStripHeight)
        try {
            val batches = rendered.jobBatches(supportsMultipleDocuments)
            batches.forEachIndexed { index, batch ->
                val jobName = if (batches.size == 1) {
                    "Mopria Scan & Print"
                } else {
                    "Mopria Scan & Print (${index + 1}/${batches.size})"
                }
                val submission = client.send(
                    uri = uri,
                    document = batch,
                    jobName = jobName,
                    options = coercedOptions,
                    multipleDocumentsSupported = supportsMultipleDocuments,
                )
                client.awaitJobCompletion(uri, submission.jobId)
            }
        } finally {
            rendered.delete()
        }
        Unit
    }

    /**
     * Pick grayscale when the user chose a monochrome print option; otherwise RGB when the printer
     * supports color, else grayscale for raster formats. Rendering grayscale directly keeps the
     * raster payload ~3x smaller instead of shipping RGB bytes the printer would discard.
     */
    private fun chooseColorSpace(modes: List<String>, requestedColorMode: String?): ColorSpace {
        val monochromeRequested = requestedColorMode?.equals("monochrome", ignoreCase = true) == true ||
            requestedColorMode?.equals("bi-level", ignoreCase = true) == true
        if (monochromeRequested) return ColorSpace.Grayscale
        return if (modes.any { it.equals("color", ignoreCase = true) }) ColorSpace.Rgb else ColorSpace.Grayscale
    }

    override suspend fun capabilities(printer: IntegrationDevice): PrintCapabilities? = withContext(Dispatchers.IO) {
        val host = printer.host ?: return@withContext null
        val uri = IppDiscovery.printerUri(host, printer.port ?: IppDiscovery.DEFAULT_PORT, printer.resourcePath, printer.secure)
        // Best-effort: if Get-Printer-Attributes fails, treat the printer as having no configurable options
        // rather than blocking the print. The sheet simply won't be shown.
        runCatching {
            val client = IppPrintClient(BoundedIppTransport())
            client.parseCapabilities(client.getPrinterAttributes(uri))
        }.getOrNull()
    }

    /**
     * Renders a [MopriaDocument] into the negotiated IPP [format] for submission.
     *
     * PDF → one multi-page file via Android `PdfDocument`. JPEG/PNG → one compressed image per page;
     * PWG-Raster/PCLm → rasterized from the same PDF using `jipp-pdl`. Image pages become separate
     * Send-Document payloads when supported, otherwise separate single-document jobs. A page that
     * cannot be decoded fails loudly rather than producing a blank sheet.
     */
    private fun renderPrintDocument(
        document: MopriaDocument,
        format: String,
        dpi: Int,
        colorSpace: ColorSpace,
        pclmStripHeight: Int,
    ): RenderedPrintDocument = when (format) {
        IppDocumentFormat.PDF -> RenderedPrintDocument(format, listOf(renderDocumentPdf(document, dpi)))
        IppDocumentFormat.JPEG, IppDocumentFormat.PNG -> {
            val extension = if (format == IppDocumentFormat.JPEG) "jpg" else "png"
            val pages = buildList {
                try {
                    document.pages.forEachIndexed { index, page ->
                        add(renderPageImage(document, index, page, format, extension, dpi))
                    }
                } catch (error: Throwable) {
                    forEach(File::delete)
                    throw error
                }
            }
            RenderedPrintDocument(format, pages)
        }
        IppDocumentFormat.PWG_RASTER, IppDocumentFormat.PCLM -> {
            val pdf = renderDocumentPdf(document, dpi)
            try {
                val raster = when (format) {
                    IppDocumentFormat.PWG_RASTER -> IppRasterizer.rasterizeToPwgRaster(pdf, dpi, colorSpace)
                    IppDocumentFormat.PCLM -> IppRasterizer.rasterizeToPclm(pdf, dpi, colorSpace, pclmStripHeight)
                    else -> error("Unsupported raster format: $format")
                }
                RenderedPrintDocument(format, listOf(raster))
            } finally {
                pdf.delete()
            }
        }
        else -> error("Unsupported render format: $format (expected PDF/JPEG/PNG/PWG-Raster/PCLm)")
    }

    private fun renderPageImage(
        document: MopriaDocument,
        index: Int,
        page: DocumentPage,
        format: String,
        extension: String,
        dpi: Int,
    ): File {
        val pageSize = document.documentSize?.toPdfPageSize() ?: PdfPageSize(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
        val renderSize = PrintRenderSizing.page(pageSize.widthPoints, pageSize.heightPoints, PDF_MARGIN, dpi)
        val source = decodePageBitmap(page, renderSize.contentWidthPixels, renderSize.contentHeightPixels)
            ?: throw PrintError.PageRenderFailed(index + 1, format)
        val output = createBitmap(renderSize.pageWidthPixels, renderSize.pageHeightPixels)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            PdfPageRenderer.drawFitted(canvas, source, renderSize.marginPixels.toFloat())
            val compressFormat = if (format == IppDocumentFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val file = File(appContext.cacheDir, "ipp-print-${document.id}-page-${index + 1}.$extension")
            try {
                FileOutputStream(file).use { out ->
                    check(output.compress(compressFormat, IPP_IMAGE_QUALITY, out)) {
                        "Could not encode page ${index + 1} as $format"
                    }
                }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
            return file
        } finally {
            source.recycle()
            output.recycle()
        }
    }

    /**
     * Renders a [MopriaDocument] to a single multi-page PDF in the cache dir for IPP submission.
     * Uses the shared [PdfPageRenderer] for PDF creation and bitmap fitting.
     */
    private fun renderDocumentPdf(document: MopriaDocument, dpi: Int): File {
        val output = File(appContext.cacheDir, "ipp-print-${document.id}.pdf")
        val pageSize = document.documentSize?.toPdfPageSize() ?: PdfPageSize(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT)
        val renderSize = PrintRenderSizing.page(pageSize.widthPoints, pageSize.heightPoints, PDF_MARGIN, dpi)
        try {
            FileOutputStream(output).use { out ->
                PdfPageRenderer.writePdf(document, out) { canvas, _, page, pageNumber ->
                    drawPrintPage(canvas, page, pageNumber, renderSize)
                }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        return output
    }

    private fun drawPrintPage(canvas: Canvas, page: DocumentPage, pageNumber: Int, renderSize: PrintRenderSize) {
        canvas.drawColor(Color.WHITE)
        val bitmap = decodePageBitmap(page, renderSize.contentWidthPixels, renderSize.contentHeightPixels)
            ?: throw PrintError.PageRenderFailed(pageNumber, IppDocumentFormat.PDF)
        try {
            PdfPageRenderer.drawFitted(canvas, bitmap, PDF_MARGIN.toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodePageBitmap(page: DocumentPage, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        page.imagePath?.let { return decodeImage(it, requestedWidth, requestedHeight) }
        val pdfPath = page.pdfPath ?: return null
        return renderPdfPage(pdfPath, page.pdfPageIndex ?: 0, requestedWidth, requestedHeight)
    }

    private fun decodeImage(path: String, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val file = File(path.removePrefix("file://"))
        if (!file.isFile) return null
        // Decode to the bounded print-resolution content area, not the 72-dpi PDF point dimensions.
        return runCatching {
            SampledBitmapDecoder.decodeFile(file.absolutePath, requestedWidth, requestedHeight)
        }.getOrNull()
    }

    private fun renderPdfPage(path: String, pageIndex: Int, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        val file = File(path.removePrefix("file://"))
        if (!file.isFile) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(requestedWidth.toFloat() / page.width, requestedHeight.toFloat() / page.height)
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
        val resolvedHost = requireNotNull(host) { "Scanner missing resolved host" }
            .let { value ->
                val encoded = value.replace("%", "%25")
                if (encoded.contains(":") && !encoded.startsWith("[") && !encoded.endsWith("]")) "[$encoded]" else encoded
            }
        val resolvedPort = port ?: if (secure) 443 else 80
        val scheme = if (secure) "https" else "http"
        return "$scheme://$resolvedHost:$resolvedPort/${resourcePath.trim('/')}"
    }

    private fun validateScannerReady(status: EsclScannerStatus, source: ScanInputSource) {
        if (!status.state.equals("Idle", true)) throw ScanError.ScannerNotReady(status.state)
        if (source == ScanInputSource.Adf) {
            val adfState = status.adfState ?: return
            if (!adfState.equals("ScannerAdfLoaded", true) && !adfState.equals("ScannerAdfProcessing", true)) {
                throw ScanError.AdfNotReady(adfState)
            }
        }
    }

    private fun finishScanJob(baseUrl: String, jobUrl: String, location: String) {
        val state = runCatching { jobTransferState(baseUrl, jobUrl) }.getOrNull()
        if (state == JobTransferState.Active) {
            runCatching { httpClient.cancelScanJob(baseUrl, location) }
        }
    }

    private suspend fun awaitJobReady(baseUrl: String, jobUrl: String) {
        repeat(JOB_READY_ATTEMPTS) {
            val job = httpClient.fetchScannerStatus(baseUrl).jobFor(jobUrl)
            when {
                job == null -> Unit
                job.state.equals("Pending", true) || job.state.equals("Processing", true) || job.state.equals("Completed", true) -> return
                job.state.equals("Canceled", true) || job.state.equals("Aborted", true) -> {
                    throw ScanError.JobAborted(job.state)
                }
            }
            delay(JOB_STATUS_POLL_MS)
        }
        throw ScanError.JobTimeout
    }

    private fun jobTransferState(baseUrl: String, jobUrl: String): JobTransferState {
        val job = httpClient.fetchScannerStatus(baseUrl).jobFor(jobUrl) ?: return JobTransferState.Missing
        return when {
            job.state.equals("Pending", true) || job.state.equals("Processing", true) -> JobTransferState.Active
            job.state.equals("Completed", true) -> JobTransferState.Completed
            job.state.equals("Canceled", true) || job.state.equals("Aborted", true) -> {
                throw ScanError.JobAborted(job.state)
            }
            else -> JobTransferState.Missing
        }
    }

    private fun pdfPageCount(file: File): Int {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        }.also { require(it > 0) { "Scanner-returned PDF has no pages" } }
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
        const val PDF_PAGE_WIDTH = 595 // A4, points
        const val PDF_PAGE_HEIGHT = 842
        // The printer owns its non-printable edge; do not add an application-side scale-down.
        const val PDF_MARGIN = 0
        const val IPP_IMAGE_QUALITY = 90
        const val JOB_READY_ATTEMPTS = 60
        const val JOB_STATUS_POLL_MS = 500L
        const val ADF_NEXT_DOCUMENT_DELAY_MS = 750L
        const val NEXT_DOCUMENT_STATUS_RETRIES = 3
        const val HTTP_GONE = 410

        fun colorModeLabel(value: String): String = ScanColorMode.entries
            .firstOrNull { it.eSclValue.equals(value, ignoreCase = true) }
            ?.displayToken
            ?: value

        fun resolutionLabel(settings: EsclNegotiatedSettings): String = if (settings.resolution == settings.yResolution) {
            "${settings.resolution} dpi"
        } else {
            "${settings.resolution}×${settings.yResolution} dpi"
        }
    }
}
