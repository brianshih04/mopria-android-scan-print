package com.brianshih.mopria.android.scanprint.domain

import java.util.Locale

data class EsclDiscoveryMetadata(
    val resourcePath: String,
    val uuid: String?,
    val version: String?,
    val displayName: String?,
    val documentFormats: List<String>,
)

object EsclDiscovery {
    fun parse(attributes: Map<String, ByteArray>): EsclDiscoveryMetadata? {
        fun value(key: String): String? = attributes.entries
            .firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)

        val resourcePath = sanitizeResourcePath(value("rs") ?: "eSCL") ?: return null
        return EsclDiscoveryMetadata(
            resourcePath = resourcePath,
            uuid = value("uuid"),
            version = value("vers") ?: EsclProtocol.DEFAULT_VERSION,
            displayName = value("ty"),
            documentFormats = value("pdl")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
        )
    }

    fun identity(serviceName: String, metadata: EsclDiscoveryMetadata): String {
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

    private fun sanitizeResourcePath(value: String): String? {
        val normalized = value.trim().trim('/')
        if (normalized.isBlank() || normalized.contains('?') || normalized.contains('#') || normalized.contains('\\') || normalized.contains('%')) return null
        val segments = normalized.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }
}
