package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Types
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IppPrintClientTest {

    @Test
    fun negotiatesFormatAndPrintsPdfAndJpeg() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val captured = Capture()

        server.createContext("/ipp/print") { exchange ->
            val packet = exchange.requestBody.use { body ->
                val input = IppInputStream(body)
                val read = input.readPacket()
                when (read.operation.code) {
                    Operation.getPrinterAttributes.code ->
                        captured.charset = read.getString(Tag.operationAttributes, Types.attributesCharset)
                    Operation.sendDocument.code -> {
                        captured.documentFormat = read.getString(Tag.operationAttributes, Types.documentFormat)
                        captured.documentBytes = input.readBytes()
                    }
                }
                read
            }
            val response = when (packet.operation.code) {
                Operation.getPrinterAttributes.code ->
                    IppPacket.Builder(SUCCESS).putPrinterAttributes(
                        Types.documentFormatSupported.of(IppDocumentFormat.PDF, IppDocumentFormat.JPEG, IppDocumentFormat.PWG_RASTER),
                    ).build()
                Operation.createJob.code ->
                    IppPacket.Builder(SUCCESS).putJobAttributes(Types.jobId.of(JOB_ID)).build()
                else -> IppPacket.Builder(SUCCESS).build()
            }
            respond(exchange, response)
        }
        server.executor = executor
        server.start()

        try {
            val uri = URI.create("http://127.0.0.1:${server.address.port}/ipp/print")
            val client = IppPrintClient(BoundedIppTransport())

            val attributes = client.getPrinterAttributes(uri)
            assertEquals(SUCCESS, attributes.status.code)
            // jipp must auto-supply the mandatory attributes-charset (PWG/RFC 8011 requires it).
            assertEquals("utf-8", captured.charset)

            // Format support varies per printer (PWG): query and negotiate before sending.
            val supported = client.documentFormatsSupported(attributes)
            assertEquals(listOf(IppDocumentFormat.PDF, IppDocumentFormat.JPEG, IppDocumentFormat.PWG_RASTER), supported)
            assertEquals(IppDocumentFormat.PDF, IppDocumentFormat.select(supported, IppDocumentFormat.producible))
            assertEquals(IppDocumentFormat.JPEG, IppDocumentFormat.select(listOf(IppDocumentFormat.JPEG), listOf(IppDocumentFormat.PDF, IppDocumentFormat.JPEG)))
            assertNull(IppDocumentFormat.select(listOf(IppDocumentFormat.URF), listOf(IppDocumentFormat.PDF, IppDocumentFormat.JPEG)))

            // Multi-format: the same client/printer prints both PDF and JPEG.
            val pdf = "%PDF-1.4 fixture".toByteArray(Charsets.UTF_8)
            val pdfJob = client.print(uri, IppDocumentFormat.PDF, ByteArrayInputStream(pdf))
            assertEquals(JOB_ID, pdfJob.jobId)
            assertEquals(SUCCESS, pdfJob.response.status.code)
            assertEquals(IppDocumentFormat.PDF, captured.documentFormat)
            assertArrayEquals(pdf, captured.documentBytes)

            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            val jpegJob = client.print(uri, IppDocumentFormat.JPEG, ByteArrayInputStream(jpeg))
            assertEquals(SUCCESS, jpegJob.response.status.code)
            assertEquals(IppDocumentFormat.JPEG, captured.documentFormat)
            assertArrayEquals(jpeg, captured.documentBytes)

            // High-level: query → negotiate → print in one call.
            val negotiated = client.printSupported(uri, ByteArrayInputStream(pdf))
            assertNotNull(negotiated)
            assertEquals(JOB_ID, negotiated!!.jobId)
            assertEquals(IppDocumentFormat.PDF, captured.documentFormat)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun respond(exchange: HttpExchange, packet: IppPacket) {
        val out = ByteArrayOutputStream()
        IppOutputStream(out).apply { write(packet); flush() }
        val bytes = out.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/ipp")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class Capture {
        var charset: String? = null
        var documentFormat: String? = null
        var documentBytes: ByteArray = ByteArray(0)
    }

    private companion object {
        const val JOB_ID = 42
        const val SUCCESS = 0x0000 // IPP successful-ok (RFC 8011 §13)
    }
}
