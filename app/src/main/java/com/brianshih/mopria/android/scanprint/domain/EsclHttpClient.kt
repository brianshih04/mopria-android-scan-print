package com.brianshih.mopria.android.scanprint.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class EsclDocumentPayload(
    val file: File,
    val contentType: String,
)

class EsclHttpException(
    val statusCode: Int,
    message: String,
) : IOException(message)

class EsclNextDocumentTimeoutException(
    message: String,
    cause: SocketTimeoutException,
) : IOException(message, cause)

/** Bounded eSCL HTTP transport with secure redirect and Retry-After policy. */
class EsclHttpClient(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRetries: Int = 4,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    fun fetchCapabilities(baseUrl: String): String {
        val response = request("GET", "$baseUrl/ScannerCapabilities")
        requireSuccessful(response, "取得 ScannerCapabilities", setOf(HttpURLConnection.HTTP_OK))
        return response.body.toString(Charsets.UTF_8)
    }

    fun fetchScannerStatus(baseUrl: String): EsclScannerStatus {
        val response = request("GET", "$baseUrl/ScannerStatus")
        requireSuccessful(response, "取得 ScannerStatus", setOf(HttpURLConnection.HTTP_OK))
        return EsclProtocol.parseScannerStatus(response.body.toString(Charsets.UTF_8))
    }

    fun createScanJob(baseUrl: String, settingsXml: String): String {
        val response = request(
            method = "POST",
            url = "$baseUrl/ScanJobs",
            body = settingsXml.toByteArray(Charsets.UTF_8),
            contentType = "text/xml; charset=utf-8",
        )
        requireSuccessful(response, "建立 eSCL ScanJob", setOf(HttpURLConnection.HTTP_CREATED))
        return response.header("Location") ?: error("eSCL ScanJobs 201 回應缺少 Location header")
    }

    fun cancelScanJob(baseUrl: String, location: String) {
        val jobUrl = EsclProtocol.scanJobUrl(baseUrl, location)
        val response = request("DELETE", jobUrl)
        if (response.code !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_GONE)) {
            throw httpError(response, "取消 eSCL ScanJob")
        }
    }

    fun fetchNextDocument(url: String, destination: File): EsclDocumentPayload? {
        var currentUrl = URL(url)
        var retries = 0
        var redirects = 0
        while (true) {
            val connection = openConnection(currentUrl, "GET").apply {
                setRequestProperty("Accept", "image/jpeg, application/pdf, image/png, application/octet-stream")
                setRequestProperty("TE", "chunked")
            }
            var retryDelay: Long? = null
            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HTTP_UPGRADE_REQUIRED) {
                    require(redirects < MAX_REDIRECTS) { "eSCL secure redirect 次數過多" }
                    currentUrl = secureRedirect(
                        currentUrl,
                        connection.getHeaderField("Location"),
                        connection.getHeaderField("Upgrade"),
                        code,
                    )
                    redirects += 1
                    continue
                }
                if (code == HttpURLConnection.HTTP_UNAVAILABLE && retries < maxRetries) {
                    retryDelay = retryDelayMs(connection.getHeaderField("Retry-After"))
                } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    destination.delete()
                    return null
                } else if (code != HttpURLConnection.HTTP_OK) {
                    val detail = connection.errorStream?.use { it.readLimited(MAX_ERROR_RESPONSE_BYTES) }
                        ?.toString(Charsets.UTF_8)?.take(240).orEmpty()
                    val challenge = connection.getHeaderField("WWW-Authenticate")?.take(160)
                    throw EsclHttpException(
                        code,
                        "取得 eSCL NextDocument 失敗：HTTP $code${challenge?.let { " · authentication required: $it" }.orEmpty()}${detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                    )
                } else {
                    val contentLength = connection.contentLengthLong
                    require(contentLength <= MAX_DOCUMENT_BYTES || contentLength < 0L) {
                        "Scanned page exceeds the ${MAX_DOCUMENT_BYTES / 1_048_576} MB limit"
                    }
                    destination.parentFile?.mkdirs()
                    connection.inputStream.use { input ->
                        destination.outputStream().buffered().use { output -> input.copyLimitedTo(output, MAX_DOCUMENT_BYTES) }
                    }
                    if (destination.length() == 0L) {
                        destination.delete()
                        error("eSCL NextDocument 回傳空白 payload")
                    }
                    val contentType = normalizedContentType(connection.getHeaderField("Content-Type"), destination)
                    return EsclDocumentPayload(destination, contentType)
                }
            } catch (error: EsclHttpException) {
                destination.delete()
                throw error
            } catch (error: SocketTimeoutException) {
                destination.delete()
                throw EsclNextDocumentTimeoutException("eSCL NextDocument 等待逾時", error)
            } catch (error: IOException) {
                destination.delete()
                throw IOException("eSCL HTTP GET $currentUrl 失敗：${error.message}", error)
            } catch (error: Exception) {
                destination.delete()
                throw error
            } finally {
                connection.disconnect()
            }
            retries += 1
            sleeper(checkNotNull(retryDelay))
        }
    }

    private fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        var currentUrl = URL(url)
        var retries = 0
        var redirects = 0
        while (true) {
            val connection = openConnection(currentUrl, method).apply {
                setRequestProperty("Accept", "text/xml, application/xml")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                    setFixedLengthStreamingMode(body.size)
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readLimited(MAX_CONTROL_RESPONSE_BYTES) } ?: ByteArray(0)
                val response = HttpResponse(code, connection.headerFields, bytes)
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HTTP_UPGRADE_REQUIRED) {
                    require(redirects < MAX_REDIRECTS) { "eSCL secure redirect 次數過多" }
                    currentUrl = secureRedirect(currentUrl, response.header("Location"), response.header("Upgrade"), code)
                    redirects += 1
                    continue
                }
                if (code == HttpURLConnection.HTTP_UNAVAILABLE && retries < maxRetries) {
                    retries += 1
                    sleeper(retryDelayMs(response.header("Retry-After")))
                    continue
                }
                return response
            } catch (error: IOException) {
                throw IOException("eSCL HTTP $method $currentUrl 失敗：${error.message}", error)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(url: URL, method: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "MopriaScanPrint/0.1")
            if (this is HttpsURLConnection) sslSocketFactory = Tls13CapableSocketFactory(sslSocketFactory)
        }

    private fun secureRedirect(source: URL, location: String?, upgrade: String?, statusCode: Int): URL {
        val target = if (!location.isNullOrBlank()) {
            source.toURI().resolve(URI(location.trim()))
        } else {
            require(statusCode == HTTP_UPGRADE_REQUIRED && upgrade.orEmpty().contains("TLS", true)) {
                "eSCL HTTP $statusCode 要求安全連線，但未提供有效的 Location 或 TLS Upgrade"
            }
            URI("https", null, source.host, 443, source.path, source.query, null)
        }
        require(source.host.equals(target.host, true)) { "拒絕跨主機 eSCL redirect" }
        require(source.path == target.path && source.query == target.query) { "eSCL redirect 必須保留 relative URL" }
        require(source.protocol.equals("http", true) && target.scheme.equals("https", true)) {
            "eSCL redirect 只能從 HTTP 升級到 HTTPS"
        }
        require(target.userInfo == null && target.fragment == null) { "eSCL redirect URL 格式不安全" }
        return target.toURL()
    }

    private fun retryDelayMs(value: String?): Long {
        val seconds = value?.trim()?.toLongOrNull()
        val millis = if (seconds != null) {
            seconds.coerceIn(0L, MAX_RETRY_DELAY_MS / 1_000L) * 1_000L
        } else {
            runCatching {
                val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                max(0L, retryAt.toEpochMilli() - Instant.now().toEpochMilli())
            }.getOrDefault(DEFAULT_RETRY_DELAY_MS)
        }
        return millis.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS)
    }

    private fun normalizedContentType(header: String?, file: File): String {
        val declared = header.orEmpty().substringBefore(';').trim().lowercase()
        val detected = file.inputStream().buffered().use { input ->
            val signature = ByteArray(8)
            val read = input.read(signature)
            when {
                read >= 4 && signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() -> "image/jpeg"
                read >= 5 && signature.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) -> "application/pdf"
                read >= 8 && signature.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
                else -> null
            }
        }
        val supported = setOf("image/jpeg", "application/pdf", "image/png")
        val result = detected ?: declared.takeIf { it in supported }
        requireNotNull(result) { "eSCL NextDocument 回傳不支援的 Content-Type：${declared.ifBlank { "missing" }}" }
        if (declared in supported) require(declared == result) { "eSCL Content-Type 與 payload signature 不一致" }
        return result
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
        if (response.code !in acceptedCodes) throw httpError(response, operation)
    }

    private fun httpError(response: HttpResponse, operation: String): EsclHttpException {
        val detail = response.body.toString(Charsets.UTF_8).take(240)
        val challenge = response.header("WWW-Authenticate")?.take(160)
        return EsclHttpException(
            response.code,
            "$operation 失敗：HTTP ${response.code}${challenge?.let { " · authentication required: $it" }.orEmpty()}${detail.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
        )
    }

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String?, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers.entries
            .firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
        const val MAX_REDIRECTS = 2
        const val MIN_RETRY_DELAY_MS = 100L
        const val DEFAULT_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 30_000L
        const val MAX_CONTROL_RESPONSE_BYTES = 2L * 1_048_576
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
        const val MAX_DOCUMENT_BYTES = 100L * 1_048_576
    }
}

private class Tls13CapableSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configure(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        val ssl = socket as? SSLSocket ?: return socket
        if ("TLSv1.3" !in ssl.supportedProtocols) {
            ssl.close()
            throw SSLHandshakeException("此 Android TLS provider 不支援 eSCL v2.97 要求的 TLS 1.3")
        }
        ssl.enabledProtocols = (ssl.enabledProtocols + "TLSv1.3").distinct().toTypedArray()
        return ssl
    }
}
