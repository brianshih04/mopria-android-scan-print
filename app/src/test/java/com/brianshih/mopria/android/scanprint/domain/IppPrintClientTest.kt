package com.brianshih.mopria.android.scanprint.domain

import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.JobState
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Types
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IppPrintClientTest {

    @Test
    fun selectsFirstProducibleFormatAndSendsSingleDocumentForPdf() {
        val server = stubPrinter(formats = listOf(IppDocumentFormat.PDF, IppDocumentFormat.JPEG, IppDocumentFormat.PWG_RASTER))
        server.start()
        try {
            val uri = server.uri
            val client = IppPrintClient(BoundedIppTransport())

            // Negotiation prefers PDF (first in producible).
            assertEquals(IppDocumentFormat.PDF, client.selectProducibleFormat(uri))

            // A PDF RenderedPrintDocument is a single file → one Send-Document with lastDocument=true.
            val pdfBytes = "%PDF-1.4 fixture".toByteArray(Charsets.UTF_8)
            val rendered = RenderedPrintDocument(IppDocumentFormat.PDF, listOf(tempFile(pdfBytes)))
            try {
                val job = client.send(uri, rendered)
                assertEquals(JOB_ID, job.jobId)
                assertEquals(SUCCESS, job.response.status.code)
            } finally {
                rendered.delete()
            }

            assertEquals(1, server.sentDocuments.size)
            val sent = server.sentDocuments.single()
            assertEquals(IppDocumentFormat.PDF, sent.documentFormat)
            assertTrue(sent.lastDocument)
            assertArrayEquals(pdfBytes, sent.documentBytes)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sendsOneDocumentPerPageWithLastDocumentOnlyOnFinalImage() {
        val server = stubPrinter(formats = listOf(IppDocumentFormat.JPEG))
        server.start()
        try {
            val uri = server.uri
            val client = IppPrintClient(BoundedIppTransport())

            assertEquals(IppDocumentFormat.JPEG, client.selectProducibleFormat(uri))

            // A multi-page JPEG document is one file per page; each page is its own Send-Document and
            // only the final one carries last-document=true.
            val pageBytes = listOf(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte()),
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0xFF.toByte(), 0xD9.toByte()),
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x03, 0xFF.toByte(), 0xD9.toByte()),
            )
            val rendered = RenderedPrintDocument(IppDocumentFormat.JPEG, pageBytes.map(::tempFile))
            try {
                client.send(uri, rendered)
            } finally {
                rendered.delete()
            }

            assertEquals(3, server.sentDocuments.size)
            assertEquals(listOf(false, false, true), server.sentDocuments.map { it.lastDocument })
            server.sentDocuments.map { it.documentFormat }.forEach { assertEquals(IppDocumentFormat.JPEG, it) }
            server.sentDocuments.map { it.documentBytes }.forEachIndexed { index, bytes -> assertArrayEquals(pageBytes[index], bytes) }
        } finally {
            server.stop()
        }
    }

    @Test
    fun selectProducibleFormatReturnsNullWhenNoProducibleFormatMatches() {
        val server = stubPrinter(formats = listOf(IppDocumentFormat.URF))
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())

            // producible is PDF/JPEG/PNG; a URF-only printer cannot be served — must NOT send.
            assertNull(client.selectProducibleFormat(server.uri))
            assertTrue(server.sentDocuments.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun sendUsesFixedLengthStreamingNotChunked() {
        val server = stubPrinter(formats = listOf(IppDocumentFormat.PDF))
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())
            val pdfBytes = "%PDF-1.4 fixture".toByteArray(Charsets.UTF_8)
            val rendered = RenderedPrintDocument(IppDocumentFormat.PDF, listOf(tempFile(pdfBytes)))
            try {
                client.send(server.uri, rendered)
            } finally {
                rendered.delete()
            }
            // Fixed-length streaming advertises Content-Length; chunked would omit it. Some printer
            // firmware rejects chunked Send-Document requests (DIRECT_IPP_FOLLOWUPS P2.8).
            assertNotNull(server.lastRequestContentLength)
        } finally {
            server.stop()
        }
    }

    @Test
    fun awaitJobCompletionReturnsWhenJobReachesCompleted() {
        val server = stubPrinter(
            formats = listOf(IppDocumentFormat.PDF),
            jobStates = listOf(JobState.pending, JobState.processing, JobState.completed),
        )
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())
            val response = runBlocking {
                client.awaitJobCompletion(server.uri, JOB_ID, pollIntervalMs = 5L, totalTimeoutMs = 5_000L)
            }
            assertEquals(JobState.completed, response.getValue(Tag.jobAttributes, Types.jobState))
            assertEquals(0, server.cancelCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun awaitJobCompletionThrowsWhenJobIsAborted() {
        val server = stubPrinter(
            formats = listOf(IppDocumentFormat.PDF),
            jobStates = listOf(JobState.processing, JobState.aborted),
        )
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())
            assertThrows(PrintError.JobAborted::class.java) {
                runBlocking {
                    client.awaitJobCompletion(server.uri, JOB_ID, pollIntervalMs = 5L, totalTimeoutMs = 5_000L)
                }
            }
            // Aborted is a printer-side failure, not a client timeout → no Cancel-Job.
            assertEquals(0, server.cancelCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun awaitJobCompletionThrowsWhenJobIsCanceled() {
        val server = stubPrinter(
            formats = listOf(IppDocumentFormat.PDF),
            jobStates = listOf(JobState.processing, JobState.canceled),
        )
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())
            assertThrows(PrintError.JobCanceled::class.java) {
                runBlocking {
                    client.awaitJobCompletion(server.uri, JOB_ID, pollIntervalMs = 5L, totalTimeoutMs = 5_000L)
                }
            }
            assertEquals(0, server.cancelCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun awaitJobCompletionCancelsJobOnTimeout() {        // Printer stays in processing forever; the client must give up and issue Cancel-Job.
        val server = stubPrinter(
            formats = listOf(IppDocumentFormat.PDF),
            jobStates = listOf(JobState.processing),
        )
        server.start()
        try {
            val client = IppPrintClient(BoundedIppTransport())
            assertThrows(PrintError.JobTimeout::class.java) {
                runBlocking {
                    client.awaitJobCompletion(server.uri, JOB_ID, pollIntervalMs = 5L, totalTimeoutMs = 60L)
                }
            }
            assertEquals(1, server.cancelCount)
        } finally {
            server.stop()
        }
    }

    private fun stubPrinter(
        formats: List<String> = listOf(IppDocumentFormat.PDF),
        jobStates: List<JobState> = emptyList(),
    ): StubIppPrinter = StubIppPrinter(formats, jobStates)

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("ipp-test", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    private class StubIppPrinter(
        formats: List<String>,
        jobStates: List<JobState>,
    ) {
        val sentDocuments = mutableListOf<SentDocument>()
        var cancelCount: Int = 0
            private set
        /** Content-Length header of the most recent request (fixed-length streaming sets this; chunked does not). */
        var lastRequestContentLength: String? = null
            private set
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newSingleThreadExecutor()
        private var jobStateIndex = 0

        init {
            server.createContext("/ipp/print") { exchange ->
                lastRequestContentLength = exchange.requestHeaders.getFirst("Content-Length")
                val input = IppInputStream(exchange.requestBody)
                val packet = input.readPacket()
                if (packet.operation.code == Operation.sendDocument.code) {
                    sentDocuments += SentDocument(
                        documentFormat = packet.getString(Tag.operationAttributes, Types.documentFormat) ?: "",
                        lastDocument = packet.getValue(Tag.operationAttributes, Types.lastDocument) == true,
                        documentBytes = input.readBytes(),
                    )
                }
                if (packet.operation.code == Operation.cancelJob.code) {
                    cancelCount += 1
                }
                val response = when (packet.operation.code) {
                    Operation.getPrinterAttributes.code ->
                        IppPacket.Builder(SUCCESS).putPrinterAttributes(
                            Types.documentFormatSupported.of(formats),
                        ).build()
                    Operation.createJob.code ->
                        IppPacket.Builder(SUCCESS).putJobAttributes(Types.jobId.of(JOB_ID)).build()
                    Operation.getJobAttributes.code -> {
                        val state = jobStates.getOrNull(jobStateIndex.coerceAtMost(jobStates.lastIndex))
                        if (jobStateIndex < jobStates.size) jobStateIndex += 1
                        buildJobAttributesResponse(state)
                    }
                    else -> IppPacket.Builder(SUCCESS).build()
                }
                respond(exchange, response)
            }
            server.executor = executor
        }

        private fun buildJobAttributesResponse(state: JobState?): IppPacket {
            val builder = IppPacket.Builder(SUCCESS)
            if (state != null) {
                builder.putJobAttributes(Types.jobState.of(state))
                if (state == JobState.aborted) {
                    builder.putJobAttributes(Types.jobStateReasons.of("aborted-by-system"))
                }
            }
            return builder.build()
        }

        fun start() {
            server.start()
        }

        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }

        val uri: URI get() = URI.create("http://127.0.0.1:${server.address.port}/ipp/print")

        private fun respond(exchange: HttpExchange, packet: IppPacket) {
            val out = ByteArrayOutputStream()
            IppOutputStream(out).apply { write(packet); flush() }
            val bytes = out.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/ipp")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private data class SentDocument(val documentFormat: String, val lastDocument: Boolean, val documentBytes: ByteArray)

    private companion object {
        const val JOB_ID = 42
        const val SUCCESS = 0x0000 // IPP successful-ok (RFC 8011 §13)
    }
}
