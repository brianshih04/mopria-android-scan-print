package com.brianshih.mopria.android.scanprint.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class EsclDocumentPayload(
    val file: File,
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

    fun fetchNextDocument(url: String, destination: File): EsclDocumentPayload? =
        download(url, destination)

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
            val bytes = stream?.use { it.readLimited(MAX_CONTROL_RESPONSE_BYTES) } ?: ByteArray(0)
            HttpResponse(code, connection.headerFields, bytes)
        } catch (error: IOException) {
            throw IOException("eSCL HTTP $method $url 失敗：${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, destination: File): EsclDocumentPayload? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "image/jpeg, image/png, application/pdf, application/octet-stream")
            setRequestProperty("User-Agent", "MopriaScanPrint/0.1")
        }

        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_NO_CONTENT) {
                destination.delete()
                return null
            }
            if (code !in 200..299) {
                val detail = connection.errorStream?.use { it.readLimited(MAX_ERROR_RESPONSE_BYTES) }
                    ?.toString(Charsets.UTF_8)
                    ?.take(240)
                    .orEmpty()
                error("取得 eSCL NextDocument 失敗：HTTP $code${if (detail.isBlank()) "" else " · $detail"}")
            }

            val contentLength = connection.contentLengthLong
            require(contentLength <= MAX_DOCUMENT_BYTES || contentLength < 0L) {
                "掃描頁面超過 ${MAX_DOCUMENT_BYTES / 1_048_576} MB 上限"
            }
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyLimitedTo(output, MAX_DOCUMENT_BYTES)
                }
            }
            if (destination.length() == 0L) {
                destination.delete()
                return null
            }
            EsclDocumentPayload(
                file = destination,
                contentType = connection.getHeaderField("Content-Type").orEmpty().substringBefore(';').lowercase(),
            )
        } catch (error: IOException) {
            destination.delete()
            throw IOException("eSCL HTTP GET $url 失敗：${error.message}", error)
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimitedTo(output, maxBytes)
        return output.toByteArray()
    }

    private fun InputStream.copyLimitedTo(output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "eSCL 回應超過 ${maxBytes / 1_048_576} MB 上限" }
            output.write(buffer, 0, read)
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

    private companion object {
        const val MAX_CONTROL_RESPONSE_BYTES = 2L * 1_048_576
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
        const val MAX_DOCUMENT_BYTES = 100L * 1_048_576
    }
}
