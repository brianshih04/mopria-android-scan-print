package com.brianshih.mopria.android.scanprint.domain

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class EsclHttpClientTest {
    @Test
    fun completesCapabilitiesScanJobAndNextDocumentFlow() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        var receivedSettings = ""
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        var nextDocumentRequests = 0
        val downloadedPage = File.createTempFile("escl-page", ".jpg").apply { delete() }

        server.createContext("/eSCL/ScannerCapabilities") { exchange ->
            respond(exchange, 200, "application/xml", "<scan:ScannerCapabilities><scan:ColorMode>RGB24</scan:ColorMode></scan:ScannerCapabilities>")
        }
        server.createContext("/eSCL/ScanJobs") { exchange ->
            receivedSettings = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            exchange.responseHeaders.add("Location", "/eSCL/ScanJobs/test-job")
            respond(exchange, 201, "text/plain", "")
        }
        server.createContext("/eSCL/ScanJobs/test-job/NextDocument") { exchange ->
            nextDocumentRequests += 1
            if (nextDocumentRequests == 1) respond(exchange, 200, "image/jpeg", imageBytes)
            else respond(exchange, 404, "text/plain", "")
        }
        server.executor = executor
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/eSCL"
            val client = EsclHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
            assertTrue(client.fetchCapabilities(baseUrl).contains("ScannerCapabilities"))
            val location = client.createScanJob(baseUrl, EsclProtocol.buildScanSettings())
            assertEquals("/eSCL/ScanJobs/test-job", location)
            assertTrue(receivedSettings.contains("ScanSettings"))
            assertArrayEquals(
                imageBytes,
                client.fetchNextDocument(EsclProtocol.nextDocumentUrl(baseUrl, location), downloadedPage)?.file?.readBytes(),
            )
            assertEquals(
                null,
                client.fetchNextDocument(EsclProtocol.nextDocumentUrl(baseUrl, location), downloadedPage),
            )
        } finally {
            downloadedPage.delete()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: String) =
        respond(exchange, code, contentType, body.toByteArray(Charsets.UTF_8))

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
