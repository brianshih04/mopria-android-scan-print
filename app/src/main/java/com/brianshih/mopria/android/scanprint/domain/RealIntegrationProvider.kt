package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
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
        ServiceType("_ipp._tcp.", DeviceKind.Printer, "IPP / Android Print Framework", secure = false),
        ServiceType("_ipps._tcp.", DeviceKind.Printer, "IPPS / Android Print Framework", secure = true),
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
                continuation.resume(devices.values.toList())
            }

            fun addResolvedDevice(service: ServiceType, info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                val key = "${service.kind}:${info.serviceName}:${info.serviceType}"
                devices[key] = IntegrationDevice(
                    id = "real-${key.hashCode()}",
                    name = info.serviceName,
                    kind = service.kind,
                    protocol = service.protocol,
                    isMock = false,
                    host = host,
                    port = info.port,
                    secure = service.secure,
                    serviceType = info.serviceType,
                )
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
                runCatching { nsdManager.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, listener) }
            }
            mainHandler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        }
    }

    override suspend fun scan(scanner: IntegrationDevice): MopriaDocument = withContext(Dispatchers.IO) {
        val baseUrl = scanner.eSclBaseUrl()
        val capabilitiesXml = httpClient.fetchCapabilities(baseUrl)
        val capabilities = EsclProtocol.parseCapabilities(capabilitiesXml)
        val format = capabilities.documentFormats.firstOrNull { it.equals("image/jpeg", true) }
            ?: "image/jpeg"
        val resolution = capabilities.resolutions.firstOrNull { it == 300 } ?: capabilities.resolutions.firstOrNull() ?: 300
        val colorMode = capabilities.colorModes.firstOrNull { it.equals("RGB24", true) }
            ?: capabilities.colorModes.firstOrNull()
            ?: "RGB24"
        val settings = EsclProtocol.buildScanSettings(
            documentFormat = format,
            resolution = resolution,
            colorMode = colorMode,
        )
        val location = httpClient.createScanJob(baseUrl, settings)
        val scanId = System.currentTimeMillis()
        val directory = File(appContext.filesDir, "scans").apply { mkdirs() }
        val pageFiles = buildList {
            for (index in 0 until MAX_SCAN_PAGES) {
                val payload = httpClient.fetchNextDocument(EsclProtocol.nextDocumentUrl(baseUrl, location)) ?: break
                val extension = when {
                    payload.contentType.contains("png") -> "png"
                    payload.contentType.contains("pdf") -> "pdf"
                    else -> "jpg"
                }
                val file = File(directory, "real-scan-$scanId-page-${index + 1}.$extension")
                file.writeBytes(payload.bytes)
                add(file)
            }
        }
        require(pageFiles.isNotEmpty()) { "eSCL ScanJob 沒有回傳影像" }

        MopriaDocument(
            id = "real-scan-$scanId",
            name = "真實掃描文件 ${scanId.toString().takeLast(4)}",
            sourceLabel = "${scanner.name} · eSCL",
            pages = pageFiles.mapIndexed { index, file ->
                DocumentPage(
                    id = "$scanId-page-${index + 1}",
                    pageNumber = index + 1,
                    title = "掃描頁 ${index + 1}",
                    imagePath = file.absolutePath,
                )
            },
            createdAt = scanId,
        )
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument) {
        error("Android Print Framework 必須由 Activity 開啟系統列印預覽")
    }

    private fun IntegrationDevice.eSclBaseUrl(): String {
        val resolvedHost = requireNotNull(host) { "掃描器缺少 resolved host" }
            .let { value -> if (value.contains(":") && !value.startsWith("[") && !value.endsWith("]")) "[$value]" else value }
        val resolvedPort = port ?: if (secure) 443 else 80
        val scheme = if (secure) "https" else "http"
        return "$scheme://$resolvedHost:$resolvedPort/eSCL"
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

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 3_500L
        const val MAX_SCAN_PAGES = 20
    }
}
