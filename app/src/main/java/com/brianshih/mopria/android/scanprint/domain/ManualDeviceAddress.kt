package com.brianshih.mopria.android.scanprint.domain

/**
 * Builds explicit local-network candidates when DNS-SD/mDNS discovery is unavailable, for example
 * behind an emulator NAT or a Wi-Fi network that filters multicast traffic.
 *
 * The input is deliberately limited to a host name or IPv4 address. Schemes, ports, paths, query
 * strings, and fragments are rejected before any URL is constructed.
 */
object ManualDeviceAddress {
    const val DEFAULT_SCANNER_PORT = 80
    const val DEFAULT_PRINTER_PORT = IppDiscovery.DEFAULT_PORT

    private val hostPattern = Regex("^[A-Za-z0-9.-]+$")

    fun normalize(value: String): String? {
        val candidate = value.trim().removeSuffix(".")
        if (
            candidate.isBlank() ||
            candidate.length > 253 ||
            !hostPattern.matches(candidate) ||
            candidate.startsWith(".") ||
            candidate.endsWith(".")
        ) return null

        val labels = candidate.split('.')
        if (labels.any { label ->
                label.isBlank() ||
                    label.length > 63 ||
                    label.startsWith('-') ||
                    label.endsWith('-')
            }
        ) return null

        return candidate
    }

    fun candidates(value: String): List<IntegrationDevice> {
        val host = normalize(value) ?: return emptyList()
        val identity = host.lowercase()
        return listOf(
            IntegrationDevice(
                id = "manual-scanner-$identity",
                name = host,
                kind = DeviceKind.Scanner,
                protocol = "eSCL / AirScan",
                isMock = false,
                host = host,
                port = DEFAULT_SCANNER_PORT,
                secure = false,
                serviceType = "_uscan._tcp.",
                resourcePath = "eSCL",
            ),
            IntegrationDevice(
                id = "manual-printer-$identity",
                name = host,
                kind = DeviceKind.Printer,
                protocol = "IPP",
                isMock = false,
                host = host,
                port = DEFAULT_PRINTER_PORT,
                secure = false,
                serviceType = "_ipp._tcp.",
                resourcePath = IppDiscovery.DEFAULT_RESOURCE_PATH,
            ),
        )
    }
}
