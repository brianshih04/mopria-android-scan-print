package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

/**
 * Carries IPP packets over HTTP with the app's transport discipline, implementing [IppTransport].
 *
 * Uses a raw socket speaking **HTTP/1.0** instead of `HttpURLConnection` (which is hard-wired
 * to HTTP/1.1). The Brother MFC-L2715DW firmware (debut/1.30) never completes a response to an
 * HTTP/1.1 IPP POST — the connection hangs until the client times out — while the identical
 * request framed as HTTP/1.0 succeeds immediately (verified 2026-09-16; see
 * `docs/brother-contenttype-rootcause.md` for the sibling eSCL quirk). HTTP/1.0 is valid for
 * IPP because every request carries an exact `Content-Length`, and the response is read until
 * the printer closes the connection.
 *
 * `ipps://` targets are sent through an `SSLSocketFactory` socket using the JVM default
 * (Android system trust store, no trust-all). IPP responses are size-capped so a hostile or
 * buggy printer cannot exhaust memory.
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
        return transmit(uri, packetBytes, document, contentLength)
    }

    private fun transmit(uri: URI, packetBytes: ByteArray, document: InputStream?, documentLength: Long): IppPacket {
        val port = if (uri.port > 0) uri.port else if (isSecure(uri)) 631 else 631
        val path = if (uri.path.isNullOrBlank()) "/" else uri.path
        val contentLength = packetBytes.size + documentLength

        val socket = openSocket(uri, port)
        try {
            socket.soTimeout = readTimeoutMs
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = buildString {
                append("POST ").append(path).append(" HTTP/1.0\r\n")
                append("Host: ").append(uri.host).append(':').append(port).append("\r\n")
                append("Content-Type: application/ipp\r\n")
                append("Content-Length: ").append(contentLength).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            output.write(request)
            output.write(packetBytes)
            output.flush()
            document?.copyTo(output)
            output.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val head = readUntilHeaderEnd(input)
            val headText = head.toString(Charsets.ISO_8859_1)
            val statusLine = headText.lineSequence().firstOrNull().orEmpty()
            val statusCode = Regex("""HTTP/\d\.\d\s+(\d{3})""").find(statusLine)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("IPP response missing HTTP status for $uri")
            if (statusCode !in 200..299) throw IOException("IPP HTTP $statusCode for $uri")

            val headers = parseHeaders(head)
            val body = readBody(input, headers)
            return IppInputStream(body.inputStream()).readPacket()
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun isSecure(uri: URI): Boolean = uri.scheme.equals("ipps", true) || uri.scheme.equals("https", true)

    private fun openSocket(uri: URI, port: Int): Socket {
        val address = InetSocketAddress(uri.host, port)
        val raw = Socket()
        raw.connect(address, connectTimeoutMs)
        return if (isSecure(uri)) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket(raw, uri.host, port, true)
        } else {
            raw
        }
    }

    /** Reads bytes until the CR-LF-CR-LF sequence that terminates the HTTP header block. */
    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        var previous = -1
        var beforePrevious = -1
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("IPP connection closed before response header")
            buffer.write(b)
            // Header block ends with CR LF CR LF (last byte LF preceded by CR LF CR).
            if (b == '\n'.code && previous == '\r'.code && beforePrevious == '\n'.code) break
            beforePrevious = previous
            previous = b
            require(buffer.size() <= 16 * 1_024) { "IPP response header exceeded 16 KB" }
        }
        return buffer.toByteArray()
    }

    private fun parseHeaders(head: ByteArray): Map<String, String> {
        val text = head.toString(Charsets.ISO_8859_1)
        return text.lineSequence()
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }
            .toMap()
    }

    /**
     * HTTP/1.0 responses end at connection close. Honor `Content-Length` when present,
     * otherwise read until EOF, enforcing the response size cap.
     */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val declaredLength = headers["content-length"]?.toLongOrNull()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (declaredLength != null && total >= declaredLength) break
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            require(total <= maxResponseBytes) { "IPP response exceeded ${maxResponseBytes / 1_048_576} MB cap" }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun serialize(packet: IppPacket): ByteArray {
        val buffer = ByteArrayOutputStream()
        IppOutputStream(buffer).apply { write(packet); flush() }
        return buffer.toByteArray()
    }
}
