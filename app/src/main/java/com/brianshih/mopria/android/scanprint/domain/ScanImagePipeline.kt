package com.brianshih.mopria.android.scanprint.domain

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.core.Core

/** Results from the optional native image preparation stage. */
sealed interface ScanImageResult {
    data object Applied : ScanImageResult

    data object Unchanged : ScanImageResult

    data object DroppedBlankPage : ScanImageResult

    data class Skipped(val reason: ScanImageSkipReason) : ScanImageResult

    data class Failed(val error: ScanImageError) : ScanImageResult
}

enum class ScanImageSkipReason {
    OpenCvUnavailable,
    UnsupportedPdf,
    UnsupportedFormat,
    ImageTooLarge,
}

enum class ScanImageError {
    InvalidSource,
    DecodeFailed,
    ProcessingFailed,
    EncodeFailed,
    OutputValidationFailed,
    ReplaceFailed,
}

/**
 * File-first eSCL image preparation. The HTTP client has already streamed the response to a
 * file, so this stage never creates a compressed-image ByteArray or a full-resolution Bitmap.
 */
object ScanImagePipeline {
    private const val MAX_SKEW_ANGLE = 15.0
    private const val MIN_SKEW_ANGLE = 0.1
    private const val BLANK_INK_RATIO = 0.002
    private const val JPEG_QUALITY = 92

    suspend fun process(
        source: File,
        settings: ScanSettings,
    ): ScanImageResult = withContext(Dispatchers.IO) {
        val needsOpenCv = settings.deskew || settings.autoCrop || settings.dropBlankPages
        if (!needsOpenCv) return@withContext ScanImageResult.Unchanged

        when (readFormat(source)) {
            DetectedFormat.Pdf -> return@withContext ScanImageResult.Skipped(ScanImageSkipReason.UnsupportedPdf)
            DetectedFormat.Jpeg, DetectedFormat.Png -> Unit
            null -> return@withContext ScanImageResult.Skipped(ScanImageSkipReason.UnsupportedFormat)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext ScanImageResult.Failed(ScanImageError.InvalidSource)
        }
        if (bounds.outWidth.toLong() * bounds.outHeight > BackgroundEnhancer.MAX_ENHANCEMENT_PIXELS) {
            return@withContext ScanImageResult.Skipped(ScanImageSkipReason.ImageTooLarge)
        }
        if (OpenCvRuntime.state() !is OpenCvRuntime.State.Available) {
            return@withContext ScanImageResult.Skipped(ScanImageSkipReason.OpenCvUnavailable)
        }

        val scope = MatScope()
        var temporary: File? = null
        try {
            currentCoroutineContext().ensureActive()
            val sourceMat = scope.own(Imgcodecs.imread(source.absolutePath, Imgcodecs.IMREAD_UNCHANGED))
            if (sourceMat.empty()) return@withContext ScanImageResult.Failed(ScanImageError.DecodeFailed)

            if (settings.dropBlankPages && isBlank(sourceMat, scope)) {
                return@withContext ScanImageResult.DroppedBlankPage
            }
            if (!settings.deskew && !settings.autoCrop) {
                return@withContext ScanImageResult.Unchanged
            }

            var current = sourceMat
            if (settings.deskew) current = deskew(current, scope)
            currentCoroutineContext().ensureActive()
            if (settings.autoCrop) current = autoCrop(current, scope)
            currentCoroutineContext().ensureActive()
            if (current === sourceMat) return@withContext ScanImageResult.Unchanged

            val suffix = if (readFormat(source) == DetectedFormat.Png) ".png" else ".jpg"
            temporary = File.createTempFile("${source.name}.processing-", suffix, source.parentFile)
            val params = if (suffix == ".png") {
                org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3)
            } else {
                org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            }
            val encoded = try {
                Imgcodecs.imwrite(temporary.absolutePath, current, params)
            } finally {
                params.release()
            }
            if (!encoded) return@withContext ScanImageResult.Failed(ScanImageError.EncodeFailed)
            if (!isValidEncodedImage(temporary, suffix)) {
                return@withContext ScanImageResult.Failed(ScanImageError.OutputValidationFailed)
            }
            try {
                AtomicFileReplacer.replace(temporary, source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withContext ScanImageResult.Failed(ScanImageError.ReplaceFailed)
            }
            ScanImageResult.Applied
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ScanImageResult.Failed(ScanImageError.ProcessingFailed)
        } finally {
            temporary?.delete()
            scope.close()
        }
    }

    private fun isBlank(source: Mat, scope: MatScope): Boolean {
        val gray = toGray(source, scope)
        val ink = scope.own(Mat())
        Imgproc.threshold(gray, ink, 245.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val pixels = gray.rows().toLong() * gray.cols().toLong()
        return pixels > 0 && Core.countNonZero(ink).toDouble() / pixels < BLANK_INK_RATIO
    }

    private fun deskew(source: Mat, scope: MatScope): Mat {
        val gray = toGray(source, scope)
        val threshold = scope.own(Mat())
        Imgproc.threshold(gray, threshold, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
        val kernelWidth = (source.cols() / 80).coerceIn(15, 61)
        val kernel = scope.own(
            Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(kernelWidth.toDouble(), 1.0),
            ),
        )
        val morph = scope.own(Mat())
        Imgproc.dilate(threshold, morph, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = scope.own(Mat())
        Imgproc.findContours(morph, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { scope.own(it) }
        val minimumArea = max(100.0, source.cols().toDouble() * source.rows() / 100_000.0)
        val angles = contours.mapNotNull { contour ->
            if (Imgproc.contourArea(contour) < minimumArea) return@mapNotNull null
            val points = MatOfPoint2f(*contour.toArray())
            scope.own(points)
            val rect = Imgproc.minAreaRect(points)
            var angle = rect.angle
            if (rect.size.width < rect.size.height) angle += 90.0
            angle.takeIf { abs(it) < MAX_SKEW_ANGLE }
        }.sorted()
        if (angles.isEmpty()) return source
        val angle = angles[angles.size / 2]
        if (abs(angle) < MIN_SKEW_ANGLE) return source

        val matrix = scope.own(
            Imgproc.getRotationMatrix2D(
                Point(source.cols() / 2.0, source.rows() / 2.0),
                angle,
                1.0,
            ),
        )
        val rotated = scope.own(Mat())
        val border = if (source.channels() == 1) Scalar(255.0) else Scalar(255.0, 255.0, 255.0, 255.0)
        Imgproc.warpAffine(
            source,
            rotated,
            matrix,
            source.size(),
            Imgproc.INTER_CUBIC,
            Core.BORDER_CONSTANT,
            border,
        )
        return rotated
    }

    private fun autoCrop(source: Mat, scope: MatScope): Mat {
        val gray = toGray(source, scope)
        val edges = scope.own(Mat())
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val kernel = scope.own(
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)),
        )
        Imgproc.dilate(edges, edges, kernel)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = scope.own(Mat())
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { scope.own(it) }
        val largest = contours.maxByOrNull(Imgproc::contourArea) ?: return source
        val area = Imgproc.contourArea(largest)
        val rect = Imgproc.boundingRect(largest)
        val total = source.cols().toDouble() * source.rows().toDouble()
        val hasMargin = rect.x > source.cols() * 0.02 ||
            rect.y > source.rows() * 0.02 ||
            rect.x + rect.width < source.cols() * 0.98 ||
            rect.y + rect.height < source.rows() * 0.98
        if (area < total * 0.15 || !hasMargin || rect.width < source.cols() * 0.25 || rect.height < source.rows() * 0.25) {
            return source
        }
        val roi = scope.own(source.submat(Rect(rect.x, rect.y, rect.width, rect.height)))
        val cropped = scope.own(roi.clone())
        scope.release(roi)
        return cropped
    }

    private fun toGray(source: Mat, scope: MatScope): Mat = scope.own(Mat()).also { gray ->
        when (source.channels()) {
            1 -> source.copyTo(gray)
            3 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGRA2GRAY)
            else -> error("Unsupported channel count: ${source.channels()}")
        }
    }

    private fun isValidEncodedImage(file: File, suffix: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val format = readFormat(file)
        return options.outWidth > 0 && options.outHeight > 0 &&
            ((suffix == ".png" && format == DetectedFormat.Png) ||
                (suffix == ".jpg" && format == DetectedFormat.Jpeg))
    }

    private fun readFormat(file: File): DetectedFormat? = try {
        val signature = ByteArray(8)
        FileInputStream(file).use { input ->
            val count = input.read(signature)
            when {
                count >= 4 && signature[0] == '%'.code.toByte() && signature[1] == 'P'.code.toByte() &&
                    signature[2] == 'D'.code.toByte() && signature[3] == 'F'.code.toByte() -> DetectedFormat.Pdf
                count >= 8 && signature.contentEquals(byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                )) -> DetectedFormat.Png
                count >= 2 && signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() -> DetectedFormat.Jpeg
                else -> null
            }
        }
    } catch (_: IOException) {
        null
    }

    private enum class DetectedFormat {
        Jpeg,
        Png,
        Pdf,
    }
}
