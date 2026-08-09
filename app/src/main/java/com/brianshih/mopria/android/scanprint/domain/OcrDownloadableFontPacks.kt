package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Searchable-PDF fonts that are installed only when the user requests their language pack. */
enum class OcrDownloadableFontPack(
    val language: OcrLanguagePack,
    val role: PdfBoxSearchableFontRole,
    val fileName: String,
    val downloadUrl: String,
    val byteLength: Long,
    val sha256: String,
) {
    Japanese(
        language = OcrLanguagePack.Japanese,
        role = PdfBoxSearchableFontRole.Japanese,
        fileName = "NotoSansJP-VF.ttf",
        downloadUrl = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset/NotoSansJP-VF.ttf",
        byteLength = 9_590_732L,
        sha256 = "F4B373B226668EE33A6E54B02823DCD2D1209F17159F777421AE8C2275160369",
    ),
    Korean(
        language = OcrLanguagePack.Korean,
        role = PdfBoxSearchableFontRole.Korean,
        fileName = "NotoSansKR-VF.ttf",
        downloadUrl = "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/Variable/TTF/Subset/NotoSansKR-VF.ttf",
        byteLength = 10_415_420L,
        sha256 = "9E1D729E7E2B36F9EF439DA102F8C134C10AABE46F1C843BF0ACA5C043B86F76",
    ),
    ;

    fun destination(context: Context): File = File(
        context.applicationContext.filesDir,
        "ocr/fonts/$fileName",
    )

    fun availableFile(context: Context): File? = destination(context)
        .takeIf { OcrFontPackDownloader.matchesChecksum(it, sha256) }

    fun isInstalled(context: Context): Boolean = destination(context).let { file ->
        file.isFile && file.length() == byteLength
    }

    companion object {
        fun forLanguage(language: OcrLanguagePack): OcrDownloadableFontPack? = entries
            .firstOrNull { it.language == language }
    }
}

/** Secure, bounded downloader for the two optional official Noto CJK font files. */
internal object OcrFontPackDownloader {
    private const val expectedHost = "raw.githubusercontent.com"
    private const val maximumBytes = 20L * 1024L * 1024L
    private const val connectTimeoutMs = 15_000
    private const val readTimeoutMs = 30_000

    suspend fun ensureAvailable(
        context: Context,
        pack: OcrDownloadableFontPack,
        onProgress: (Int) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val destination = pack.destination(context)
        if (matchesChecksum(destination, pack.sha256)) {
            onProgress(100)
            return@withContext true
        }
        if (destination.exists() && !destination.delete()) return@withContext false

        val url = URL(pack.downloadUrl)
        if (url.protocol != "https" || url.host != expectedHost || url.port !in listOf(-1, 443)) {
            return@withContext false
        }

        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("${pack.fileName}.", ".part", destination.parentFile)
        var connection: HttpsURLConnection? = null
        try {
            connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return@withContext false

            val contentLength = connection.contentLengthLong
            if (
                contentLength > maximumBytes ||
                contentLength > 0L && contentLength != pack.byteLength
            ) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maximumBytes) return@withContext false
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (contentLength > 0L) {
                            onProgress(((total * 100L) / contentLength).toInt().coerceIn(0, 99))
                        }
                    }
                }
            }
            if (
                total != pack.byteLength ||
                !digest.digest().toHex().equals(pack.sha256, ignoreCase = true)
            ) {
                return@withContext false
            }
            AtomicFileReplacer.replace(temporary, destination)
            onProgress(100)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
            temporary.delete()
        }
    }

    fun matchesChecksum(file: File, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > maximumBytes) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHex().equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02X".format(byte) }
}
