package com.brianshih.mopria.android.scanprint.domain

import java.net.URI
import java.util.Locale

data class IppDiscoveryMetadata(
    val resourcePath: String,
    val uuid: String?,
    val version: String?,
    val displayName: String?,
    val documentFormats: List<String>,
)

/**
 * DNS-SD discovery for IPP/IPPS printers, mirroring [EsclDiscovery] for scanners.
 *
 * IPP printers advertise `_ipp._tcp` / `_ipps._tcp` (port 631) with TXT records defined by the PWG IPP
 * guide: `rp` (resource path → printer-uri, often "ipp/print"), `ty` (model), `pdl` (supported document
 * formats), `uuid`, `vers`. The TLS service (`_ipps._tcp`) is preferred when both are seen for a device.
 */
object IppDiscovery {
    /** Resource path assumed when a printer omits `rp` (PWG/AirPrint convention). */
    const val DEFAULT_RESOURCE_PATH = "ipp/print"
    const val DEFAULT_PORT = 631

    fun parse(attributes: Map<String, ByteArray>): IppDiscoveryMetadata? {
        fun value(key: String): String? = attributes.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)

        val resourcePath = sanitizeResourcePath(value("rp") ?: DEFAULT_RESOURCE_PATH) ?: return null
        return IppDiscoveryMetadata(
            resourcePath = resourcePath,
            uuid = value("uuid"),
            version = value("vers"),
            displayName = value("ty"),
            documentFormats = value("pdl")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        )
    }

    fun identity(serviceName: String, metadata: IppDiscoveryMetadata): String {
        val resource = metadata.resourcePath.lowercase(Locale.ROOT)
        return metadata.uuid?.takeIf(String::isNotBlank)
            ?.let { "${it.lowercase(Locale.ROOT)}:$resource" }
            ?: "${serviceName.lowercase(Locale.ROOT)}:$resource"
    }

    fun preferred(existing: IntegrationDevice?, candidate: IntegrationDevice): IntegrationDevice = when {
        existing == null -> candidate
        candidate.secure && !existing.secure -> candidate
        else -> existing
    }

    /** Build the printer-uri (`ipps://` for TLS) from the resolved host/port and `rp` resource path. */
    fun printerUri(host: String, port: Int, resourcePath: String, secure: Boolean): URI {
        val path = "/" + resourcePath.trim('/')
        val scheme = if (secure) "ipps" else "ipp"
        return URI(scheme, null, host, if (port > 0) port else DEFAULT_PORT, path, null, null)
    }

    private fun sanitizeResourcePath(value: String): String? {
        val normalized = value.trim().trim('/')
        if (normalized.isBlank() || normalized.contains('?') || normalized.contains('#') || normalized.contains('\\') || normalized.contains('%')) return null
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }
}
