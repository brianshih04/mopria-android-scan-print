package com.brianshih.mopria.android.scanprint.domain

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EsclHttpClientTest {
    @Test
    fun completesStatusJobRetryDownloadAndCancelFlow() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val sleeps = mutableListOf<Long>()
        var receivedSettings = ""
        var receivedContentType = ""
        var receivedConnection = ""
        var nextDocumentRequests = 0
        var cancelRequests = 0
        val imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val downloadedPage = File.createTempFile("escl-page", ".jpg").apply { delete() }

        server.createContext("/custom/ScannerCapabilities") { exchange ->
            respond(exchange, 200, "text/xml", validCapabilities())
        }
        server.createContext("/custom/ScannerStatus") { exchange ->
            respond(exchange, 200, "text/xml", completedStatus())
        }
        server.createContext("/custom/ScanJobs/test-job/NextDocument") { exchange ->
            nextDocumentRequests += 1
            receivedConnection = exchange.requestHeaders.getFirst("Connection").orEmpty()
            if (nextDocumentRequests == 1) {
                exchange.responseHeaders.add("Retry-After", "2")
                respond(exchange, 503, "text/plain", "busy")
            } else {
                respond(exchange, 200, "image/jpeg", imageBytes)
            }
        }
        server.createContext("/custom/ScanJobs/test-job") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                cancelRequests += 1
                respond(exchange, 200, "text/plain", "")
            } else {
                respond(exchange, 405, "text/plain", "")
            }
        }
        server.createContext("/custom/ScanJobs") { exchange ->
            receivedSettings = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            receivedContentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
            exchange.responseHeaders.add("Location", "/custom/ScanJobs/test-job")
            respond(exchange, 201, "text/plain", "")
        }
        server.executor = executor
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/custom"
            val client = EsclHttpClient(2_000, 2_000, sleeper = sleeps::add)
            assertTrue(client.fetchCapabilities(baseUrl).contains("ScannerCapabilities"))
            assertEquals("Completed", client.fetchScannerStatus(baseUrl).jobs.single().state)
            val location = client.createScanJob(baseUrl, EsclProtocol.buildScanSettings())
            assertEquals("/custom/ScanJobs/test-job", location)
            assertTrue(receivedSettings.contains("<pwg:Version>"))
            assertTrue(receivedContentType.startsWith("application/xml"))
            val payload = client.fetchNextDocument(EsclProtocol.nextDocumentUrl(baseUrl, location), downloadedPage)
            assertArrayEquals(imageBytes, payload?.file?.readBytes())
            assertEquals("image/jpeg", payload?.contentType)
            assertTrue(receivedConnection.equals("close", ignoreCase = true))
            assertEquals(listOf(2_000L), sleeps)
            client.cancelScanJob(baseUrl, location)
            assertEquals(1, cancelRequests)
        } finally {
            downloadedPage.delete()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun acceptsPdfBySignatureWhenContentTypeIsOctetStream() {
        withServer { server, baseUrl ->
            val pdf = "%PDF-1.7\nfixture".toByteArray()
            server.createContext("/custom/ScanJobs/1/NextDocument") { exchange -> respond(exchange, 200, "application/octet-stream", pdf) }
            val target = File.createTempFile("escl-pdf", ".part").apply { delete() }
            try {
                val payload = EsclHttpClient(2_000, 2_000).fetchNextDocument("$baseUrl/ScanJobs/1/NextDocument", target)
                assertEquals("application/pdf", payload?.contentType)
            } finally {
                target.delete()
            }
        }
    }

    @Test
    fun rejectsCrossHostSecureRedirect() {
        withServer { server, baseUrl ->
            server.createContext("/custom/ScannerCapabilities") { exchange ->
                exchange.responseHeaders.add("Location", "https://example.com/custom/ScannerCapabilities")
                respond(exchange, 301, "text/plain", "")
            }
            assertThrows(IllegalArgumentException::class.java) { EsclHttpClient(2_000, 2_000).fetchCapabilities(baseUrl) }
        }
    }

    @Test
    fun reportsAuthenticationChallenge() {
        withServer { server, baseUrl ->
            server.createContext("/custom/ScannerStatus") { exchange ->
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"scanner\"")
                respond(exchange, 401, "text/plain", "")
            }
            val error = assertThrows(EsclHttpException::class.java) { EsclHttpClient(2_000, 2_000).fetchScannerStatus(baseUrl) }
            assertEquals(401, error.statusCode)
            assertTrue(error.message.orEmpty().contains("authentication required"))
        }
    }

    @Test
    fun requiresLocationOnCreatedScanJob() {
        withServer { server, baseUrl ->
            server.createContext("/custom/ScanJobs") { exchange -> respond(exchange, 201, "text/plain", "") }
            val error = assertThrows(IllegalStateException::class.java) {
                EsclHttpClient(2_000, 2_000).createScanJob(baseUrl, EsclProtocol.buildScanSettings())
            }
            assertTrue(error.message.orEmpty().contains("Location"))
        }
    }

    @Test
    fun treats404AsEndAndRejectsMismatchedPayloadType() {
        withServer { server, baseUrl ->
            server.createContext("/custom/ScanJobs/end/NextDocument") { exchange -> respond(exchange, 404, "text/plain", "") }
            server.createContext("/custom/ScanJobs/bad/NextDocument") { exchange ->
                respond(exchange, 200, "application/pdf", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            }
            val endTarget = File.createTempFile("escl-end", ".part").apply { delete() }
            val badTarget = File.createTempFile("escl-bad", ".part").apply { delete() }
            try {
                assertEquals(null, EsclHttpClient(2_000, 2_000).fetchNextDocument("$baseUrl/ScanJobs/end/NextDocument", endTarget))
                assertThrows(IllegalArgumentException::class.java) {
                    EsclHttpClient(2_000, 2_000).fetchNextDocument("$baseUrl/ScanJobs/bad/NextDocument", badTarget)
                }
                assertTrue(!endTarget.exists() && !badTarget.exists())
            } finally {
                endTarget.delete()
                badTarget.delete()
            }
        }
    }

    @Test
    fun rejectsUnexpectedSuccessCodesForControlAndDocumentResponses() {
        withServer { server, baseUrl ->
            server.createContext("/custom/ScannerCapabilities") { exchange ->
                respond(exchange, 201, "text/xml", validCapabilities())
            }
            server.createContext("/custom/ScanJobs/partial/NextDocument") { exchange ->
                respond(exchange, 206, "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            }
            val target = File.createTempFile("escl-partial", ".part").apply { delete() }
            try {
                assertThrows(EsclHttpException::class.java) {
                    EsclHttpClient(2_000, 2_000).fetchCapabilities(baseUrl)
                }
                assertThrows(EsclHttpException::class.java) {
                    EsclHttpClient(2_000, 2_000).fetchNextDocument("$baseUrl/ScanJobs/partial/NextDocument", target)
                }
                assertTrue(!target.exists())
            } finally {
                target.delete()
            }
        }
    }

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.start()
        try {
            block(server, "http://127.0.0.1:${server.address.port}/custom")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun validCapabilities() = """
        <scan:ScannerCapabilities xmlns:scan="${EsclProtocol.XML_NAMESPACE}" xmlns:pwg="${EsclProtocol.PWG_NAMESPACE}"><pwg:Version>2.97</pwg:Version></scan:ScannerCapabilities>
    """.trimIndent()

    private fun completedStatus() = """
        <scan:ScannerStatus xmlns:scan="${EsclProtocol.XML_NAMESPACE}" xmlns:pwg="${EsclProtocol.PWG_NAMESPACE}">
          <pwg:Version>2.97</pwg:Version><pwg:State>Idle</pwg:State><scan:Jobs><scan:JobInfo><pwg:JobUri>/custom/ScanJobs/test-job</pwg:JobUri><pwg:JobState>Completed</pwg:JobState></scan:JobInfo></scan:Jobs>
        </scan:ScannerStatus>
    """.trimIndent()

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: String) =
        respond(exchange, code, contentType, body.toByteArray(Charsets.UTF_8))

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
