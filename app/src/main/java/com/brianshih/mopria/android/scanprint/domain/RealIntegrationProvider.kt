package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Real-device integration seam.
 *
 * Discovery uses Android NSD to find the service types used by eSCL/AirScan and
 * IPP printers. Protocol capability parsing and scan-job execution stay behind
 * this same provider boundary until the eSCL implementation milestone lands.
 */
class RealIntegrationProvider(context: Context) : DeviceDiscovery, ScanAcquisitionProvider, PrintProvider {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val serviceTypes = listOf(
        ServiceType("_uscan._tcp.", DeviceKind.Scanner, "eSCL / AirScan"),
        ServiceType("_uscans._tcp.", DeviceKind.Scanner, "eSCL / AirScan"),
        ServiceType("_ipp._tcp.", DeviceKind.Printer, "IPP / Android Print Framework"),
        ServiceType("_ipps._tcp.", DeviceKind.Printer, "IPPS / Android Print Framework"),
    )

    override suspend fun discover(): List<IntegrationDevice> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val devices = linkedMapOf<String, IntegrationDevice>()
            val startedListeners = mutableSetOf<NsdManager.DiscoveryListener>()
            var finished = false
            lateinit var timeout: Runnable

            fun finish() {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(timeout)
                startedListeners.forEach { listener ->
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }
                continuation.resume(devices.values.toList())
            }

            timeout = Runnable { finish() }
            continuation.invokeOnCancellation {
                mainHandler.removeCallbacks(timeout)
                startedListeners.forEach { listener ->
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }
            }

            serviceTypes.forEach { service ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        startedListeners += this
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val key = "${service.kind}:${serviceInfo.serviceName}:${serviceInfo.serviceType}"
                        devices.putIfAbsent(
                            key,
                            IntegrationDevice(
                                id = "real-${key.hashCode()}",
                                name = serviceInfo.serviceName,
                                kind = service.kind,
                                protocol = service.protocol,
                                isMock = false,
                            ),
                        )
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
                runCatching {
                    nsdManager.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }
            mainHandler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
        }
    }

    override suspend fun scan(scanner: IntegrationDevice): MopriaDocument {
        error("真實 eSCL 掃描尚未完成，已找到 ${scanner.name}；請先切回模擬模式測試文件流程")
    }

    override suspend fun print(printer: IntegrationDevice, document: MopriaDocument) {
        error("真實設備列印 provider 尚未完成；請從文件頁開啟 Android 系統列印預覽")
    }

    private data class ServiceType(
        val type: String,
        val kind: DeviceKind,
        val protocol: String,
    )

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 1_800L
    }
}
