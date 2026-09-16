package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Carries IPP packets over HTTP with the app's transport discipline, implementing [IppTransport].
 *
 * Mirrors [EsclHttpClient]: cleartext is permitted only because IPP printers on local networks commonly
 * expose `ipp://`; `ipps://` is sent through `HttpsURLConnection` using the Android system trust store
 * (no trust-all) and the platform's default TLS negotiation. IPP responses are size-capped so a hostile
 * or buggy printer cannot exhaust memory.
 *
 * Requests use fixed-length streaming (`Content-Length`) computed from the serialized IPP packet plus
 * the known document length, rather than chunked transfer-encoding — some printer firmware rejects
 * chunked requests (see DIRECT_IPP_FOLLOWUPS.md P2.8).
 */
class BoundedIppTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxResponseBytes: Long = 2L * 1_048_576,
) : IppTransport {

    @Throws(IOException::class)
    override fun send(uri: URI, packet: IppPacket): IppPacket {
        val packetBytes = serialize(packet)
        return transmit(uri, packetBytes, document = null, documentLength = 0L)
    }

    @Throws(IOException::class)
    override fun send(uri: URI, packet: IppPacket, document: InputStream, contentLength: Long): IppPacket {
        require(contentLength >= 0) { "contentLength must be >= 0" }
        val packetBytes = serialize(packet)
        return transmit(uri, packetBytes, document, documentLength = contentLength)
    }

    private fun transmit(uri: URI, packetBytes: ByteArray, document: InputStream?, documentLength: Long): IppPacket {
        val connection = openConnection(uri, packetBytes.size + documentLength)
        return try {
            connection.outputStream.use { output ->
                output.write(packetBytes)
                output.flush()
                document?.copyTo(output)
                output.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("IPP HTTP $code for $uri")
            readBounded(connection).inputStream().use { IppInputStream(it).readPacket() }
        } finally {
            connection.disconnect()
        }
    }

    private fun serialize(packet: IppPacket): ByteArray {
        val buffer = ByteArrayOutputStream()
        IppOutputStream(buffer).apply { write(packet); flush() }
        return buffer.toByteArray()
    }

    private fun openConnection(uri: URI, contentLength: Long): HttpURLConnection =
        (URL(toHttpUrl(uri)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/ipp")
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            instanceFollowRedirects = false
            doOutput = true
            setFixedLengthStreamingMode(contentLength)
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
                require(total <= maxResponseBytes) { "IPP response exceeded ${maxResponseBytes / 1_048_576} MB cap" }
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
