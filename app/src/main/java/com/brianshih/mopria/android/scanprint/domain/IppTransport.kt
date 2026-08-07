package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.trans.IppClientTransport
import com.hp.jipp.trans.IppPacketData
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Carries jipp IPP packets over HTTP with the app's transport discipline.
 *
 * Mirrors [EsclHttpClient]: cleartext is permitted only because IPP printers on local networks
 * commonly expose `ipp://`; `ipps://` is sent through `HttpsURLConnection` using the Android system
 * trust store (no trust-all) and the platform's default TLS 1.2+ negotiation. IPP responses are
 * size-capped so a hostile or buggy printer cannot exhaust memory.
 */
class BoundedIppTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxResponseBytes: Long = 2L * 1_048_576,
) : IppClientTransport {

    @Throws(IOException::class)
    override fun sendData(uri: URI, request: IppPacketData): IppPacketData {
        val connection = openConnection(uri)
        return try {
            DataOutputStream(connection.outputStream).use { output ->
                IppOutputStream(output).apply {
                    write(request.packet)
                    flush()
                }
                request.data?.copyTo(output)
                output.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("IPP HTTP $code for $uri")
            val responseBytes = readBounded(connection)
            responseBytes.inputStream().use { input ->
                val ippInput = IppInputStream(input)
                IppPacketData(ippInput.readPacket(), ippInput)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(uri: URI): HttpURLConnection =
        (URL(toHttpUrl(uri)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/ipp")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            doOutput = true
            setChunkedStreamingMode(0)
        }

    private fun readBounded(connection: HttpURLConnection): ByteArray {
        val buffer = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                require(total <= maxResponseBytes) { "IPP 回應超過 ${maxResponseBytes / 1_048_576} MB 上限" }
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toByteArray()
    }

    private fun toHttpUrl(uri: URI): String {
        val raw = uri.toString()
        return when {
            raw.startsWith("ipps:", true) -> "https:" + raw.substringAfter(':')
            raw.startsWith("ipp:", true) -> "http:" + raw.substringAfter(':')
            else -> raw
        }
    }
}
