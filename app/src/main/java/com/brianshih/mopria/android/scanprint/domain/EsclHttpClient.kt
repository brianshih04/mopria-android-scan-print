package com.brianshih.mopria.android.scanprint.domain

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class EsclDocumentPayload(
    val bytes: ByteArray,
    val contentType: String,
)

/** Minimal HTTP transport for the eSCL endpoints; all calls are made off the main thread. */
class EsclHttpClient(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun fetchCapabilities(baseUrl: String): String {
        val response = request("GET", "$baseUrl/ScannerCapabilities")
        requireSuccessful(response, "取得 ScannerCapabilities")
        return response.body.toString(Charsets.UTF_8)
    }

    fun createScanJob(baseUrl: String, settingsXml: String): String {
        val response = request(
            method = "POST",
            url = "$baseUrl/ScanJobs",
            body = settingsXml.toByteArray(Charsets.UTF_8),
            contentType = "application/xml; charset=utf-8",
        )
        requireSuccessful(response, "建立 eSCL ScanJob", setOf(HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_OK))
        return response.header("Location")
            ?: response.body.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            ?: error("eSCL ScanJobs 回應缺少 Location")
    }

    fun fetchNextDocument(url: String): EsclDocumentPayload? {
        val response = request("GET", url)
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND || response.code == HttpURLConnection.HTTP_NO_CONTENT) {
            return null
        }
        requireSuccessful(response, "取得 eSCL NextDocument")
        if (response.body.isEmpty()) return null
        return EsclDocumentPayload(
            bytes = response.body,
            contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase(),
        )
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/xml, image/jpeg, image/png, application/pdf, application/octet-stream")
            setRequestProperty("User-Agent", "MopriaScanPrint/0.1")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                setFixedLengthStreamingMode(body.size)
            }
        }

        return try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(code, connection.headerFields, bytes)
        } catch (error: IOException) {
            throw IOException("eSCL HTTP $method $url 失敗：${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccessful(
        response: HttpResponse,
        operation: String,
        acceptedCodes: Set<Int> = (200..299).toSet(),
    ) {
        if (response.code !in acceptedCodes) {
            val detail = response.body.toString(Charsets.UTF_8).take(240)
            error("${operation}失敗：HTTP ${response.code}${if (detail.isBlank()) "" else " · $detail"}")
        }
    }

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String?, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
