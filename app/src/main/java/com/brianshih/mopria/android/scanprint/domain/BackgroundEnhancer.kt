package com.brianshih.mopria.android.scanprint.domain

import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

sealed interface EnhancementResult {
    data object Applied : EnhancementResult

    data class Skipped(val reason: EnhancementSkipReason) : EnhancementResult

    data class Failed(val error: EnhancementError) : EnhancementResult
}

enum class EnhancementSkipReason {
    OpenCvUnavailable,
    UnsupportedPdf,
    UnsupportedFormat,
    ImageTooLarge,
}

enum class EnhancementError {
    InvalidSource,
    DecodeFailed,
    ProcessingFailed,
    EncodeFailed,
    OutputValidationFailed,
    ReplaceFailed,
}

/** One cached initialization result for the OpenCV native runtime. */
object OpenCvRuntime {
    sealed interface State {
        data object Available : State

        data class Unavailable(val reason: UnavailabilityReason) : State
    }

    enum class UnavailabilityReason {
        InitializationFailed,
    }

    @Volatile
    private var cachedState: State? = null

    fun state(): State = cachedState ?: synchronized(this) {
        cachedState ?: initialize().also { cachedState = it }
    }

    private fun initialize(): State = try {
        if (OpenCVLoader.initLocal()) {
            State.Available
        } else {
            State.Unavailable(UnavailabilityReason.InitializationFailed)
        }
    } catch (_: Exception) {
        State.Unavailable(UnavailabilityReason.InitializationFailed)
    } catch (_: LinkageError) {
        State.Unavailable(UnavailabilityReason.InitializationFailed)
    }
}

/** Owns OpenCV matrices and releases every matrix on success, failure, and cancellation. */
class MatScope : AutoCloseable {
    private val mats = ArrayDeque<Mat>()

    fun own(mat: Mat): Mat = mat.also(mats::addFirst)

    fun release(mat: Mat) {
        val iterator = mats.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === mat) {
                iterator.remove()
                mat.release()
                return
            }
        }
    }

    override fun close() {
        while (mats.isNotEmpty()) {
            mats.removeFirst().release()
        }
    }
}

internal enum class EnhancementImageFormat {
    Jpeg,
    Png,
}

internal sealed interface EnhancementInput {
    data object Invalid : EnhancementInput
    data object Pdf : EnhancementInput
    data object Unsupported : EnhancementInput
    data class Image(
        val format: EnhancementImageFormat,
        val width: Int,
        val height: Int,
    ) : EnhancementInput
}

internal fun interface EnhancementInputInspector {
    fun inspect(source: File): EnhancementInput
}

internal fun interface EnhancementEncoder {
    suspend fun encode(
        source: File,
        destination: File,
        format: EnhancementImageFormat,
        strength: EnhancementStrength,
    ): EnhancementError?
}

internal fun interface EnhancementOutputValidator {
    fun validate(file: File, format: EnhancementImageFormat, width: Int, height: Int): Boolean
}

internal fun interface EnhancementFileReplacer {
    fun replace(temporary: File, target: File)
}

internal fun interface EnhancementTemporaryFileFactory {
    fun create(source: File, format: EnhancementImageFormat): File
}

internal data class EnhancementDependencies(
    val inspector: EnhancementInputInspector,
    val runtimeAvailable: () -> Boolean,
    val encoder: EnhancementEncoder,
    val validator: EnhancementOutputValidator,
    val replacer: EnhancementFileReplacer,
    val temporaryFileFactory: EnhancementTemporaryFileFactory,
)

/**
 * File-first background cleanup. The legacy pixel function remains for JVM algorithm fixtures;
 * production file processing uses the OpenCV path below and never overwrites the source directly.
 */
object BackgroundEnhancer {
    const val MAX_ENHANCEMENT_PIXELS = 12_000_000L

    private const val DOWNSAMPLE_FACTOR = 4
    private const val MIN_BACKGROUND_LEVEL = 128
    private const val JPEG_QUALITY = 92
    // Keep the seven reusable strip buffers below ~5 MiB for a 2,480 px-wide A4 page. A larger
    // strip crossed the app's 256 MB absolute PSS gate after a ten-page allocator warm-up.
    private const val STRIP_HEIGHT = 128

    private val productionDependencies = EnhancementDependencies(
        inspector = EnhancementInputInspector(::inspect),
        runtimeAvailable = { OpenCvRuntime.state() is OpenCvRuntime.State.Available },
        encoder = EnhancementEncoder(::encodeWithOpenCv),
        validator = EnhancementOutputValidator(::isValidEncodedImage),
        replacer = EnhancementFileReplacer(AtomicFileReplacer::replace),
        temporaryFileFactory = EnhancementTemporaryFileFactory { source, format ->
            val parent = source.parentFile ?: throw IOException("Source has no parent directory")
            val suffix = if (format == EnhancementImageFormat.Png) ".png" else ".jpg"
            File.createTempFile("${source.name}.enhancing-", suffix, parent)
        },
    )

    /**
     * Enhance a scanner-returned JPEG or PNG using a sibling temporary file and atomic replace.
     * Unsupported input and unavailable native runtime preserve the source and return [Skipped].
     * All processing failures preserve the source and return [Failed].
     */
    suspend fun enhanceImageFile(
        source: File,
        strength: EnhancementStrength = EnhancementStrength.Normal,
    ): EnhancementResult = enhanceImageFile(source, strength, productionDependencies)

    internal suspend fun enhanceImageFile(
        source: File,
        strength: EnhancementStrength,
        dependencies: EnhancementDependencies,
    ): EnhancementResult = withContext(Dispatchers.IO) {
        val input = when (val inspection = dependencies.inspector.inspect(source)) {
            EnhancementInput.Invalid -> return@withContext EnhancementResult.Failed(EnhancementError.InvalidSource)
            EnhancementInput.Pdf -> return@withContext EnhancementResult.Skipped(EnhancementSkipReason.UnsupportedPdf)
            EnhancementInput.Unsupported -> return@withContext EnhancementResult.Skipped(EnhancementSkipReason.UnsupportedFormat)
            is EnhancementInput.Image -> {
                if (inspection.width.toLong() * inspection.height > MAX_ENHANCEMENT_PIXELS) {
                    return@withContext EnhancementResult.Skipped(EnhancementSkipReason.ImageTooLarge)
                }
                inspection
            }
        }

        if (!dependencies.runtimeAvailable()) {
            return@withContext EnhancementResult.Skipped(EnhancementSkipReason.OpenCvUnavailable)
        }

        currentCoroutineContext().ensureActive()
        val temporary = try {
            dependencies.temporaryFileFactory.create(source, input.format)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext EnhancementResult.Failed(EnhancementError.EncodeFailed)
        }

        try {
            currentCoroutineContext().ensureActive()
            dependencies.encoder.encode(source, temporary, input.format, strength)?.let { error ->
                return@withContext EnhancementResult.Failed(error)
            }
            currentCoroutineContext().ensureActive()
            if (!dependencies.validator.validate(temporary, input.format, input.width, input.height)) {
                return@withContext EnhancementResult.Failed(EnhancementError.OutputValidationFailed)
            }

            currentCoroutineContext().ensureActive()
            try {
                dependencies.replacer.replace(temporary, source)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withContext EnhancementResult.Failed(EnhancementError.ReplaceFailed)
            }
            EnhancementResult.Applied
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            EnhancementResult.Failed(EnhancementError.ProcessingFailed)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun encodeWithOpenCv(
        source: File,
        destination: File,
        format: EnhancementImageFormat,
        strength: EnhancementStrength,
    ): EnhancementError? {
        val scope = MatScope()
        try {
            val sourceMat = scope.own(Imgcodecs.imread(source.absolutePath, Imgcodecs.IMREAD_UNCHANGED))
            if (sourceMat.empty()) return EnhancementError.DecodeFailed

            currentCoroutineContext().ensureActive()
            val enhanced = withContext(Dispatchers.Default) {
                currentCoroutineContext().ensureActive()
                enhanceMat(sourceMat, strength, scope)
            }
            currentCoroutineContext().ensureActive()

            val parameters = if (format == EnhancementImageFormat.Png) {
                MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 3)
            } else {
                MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
            }
            val encoded = try {
                Imgcodecs.imwrite(destination.absolutePath, enhanced, parameters)
            } catch (_: Exception) {
                false
            } finally {
                parameters.release()
            }
            return if (encoded) null else EnhancementError.EncodeFailed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return EnhancementError.ProcessingFailed
        } finally {
            scope.close()
        }
    }

    private suspend fun enhanceMat(
        source: Mat,
        strength: EnhancementStrength,
        scope: MatScope,
    ): Mat {
        require(source.channels() in 1..4 && source.channels() != 2) {
            "Unsupported channel count: ${source.channels()}"
        }

        val smallSize = Size(
            max(1, source.cols() / DOWNSAMPLE_FACTOR).toDouble(),
            max(1, source.rows() / DOWNSAMPLE_FACTOR).toDouble(),
        )
        val smallColor = scope.own(Mat())
        when (source.channels()) {
            1 -> {
                val smallGraySource = scope.own(Mat())
                Imgproc.resize(source, smallGraySource, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(smallGraySource, smallColor, Imgproc.COLOR_GRAY2BGR)
                scope.release(smallGraySource)
            }
            3 -> Imgproc.resize(source, smallColor, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            4 -> {
                val smallBgra = scope.own(Mat())
                Imgproc.resize(source, smallBgra, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(smallBgra, smallColor, Imgproc.COLOR_BGRA2BGR)
                scope.release(smallBgra)
            }
        }
        val smallGray = scope.own(Mat())
        Imgproc.cvtColor(smallColor, smallGray, Imgproc.COLOR_BGR2GRAY)
        scope.release(smallColor)

        val kernelSize = (max(smallGray.cols(), smallGray.rows()) / 16)
            .coerceIn(3, 31)
            .let { if (it % 2 == 0) it + 1 else it }
        val kernel = scope.own(
            Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
            ),
        )
        val smallBackground = scope.own(Mat())
        Imgproc.morphologyEx(smallGray, smallBackground, Imgproc.MORPH_CLOSE, kernel)
        scope.release(smallGray)
        scope.release(kernel)

        val background = scope.own(Mat())
        Imgproc.resize(smallBackground, background, source.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        scope.release(smallBackground)

        val parameters = strengthParameters(strength)
        val output = scope.own(Mat(source.rows(), source.cols(), source.type()))
        val bufferScope = MatScope()
        try {
            val colorBuffer = bufferScope.own(Mat())
            val lowBackgroundMask = bufferScope.own(Mat())
            val safeBackground = bufferScope.own(Mat())
            val colorBackground = bufferScope.own(Mat())
            val normalized = bufferScope.own(Mat())
            val contrast = bufferScope.own(Mat())
            val whiteMask = bufferScope.own(Mat())

            var top = 0
            while (top < source.rows()) {
                currentCoroutineContext().ensureActive()
                val height = min(STRIP_HEIGHT, source.rows() - top)
                val rect = Rect(0, top, source.cols(), height)
                val stripScope = MatScope()
                try {
                    val sourceStrip = stripScope.own(source.submat(rect))
                    val colorStrip = when (source.channels()) {
                        1 -> colorBuffer.also {
                            Imgproc.cvtColor(sourceStrip, it, Imgproc.COLOR_GRAY2BGR)
                        }
                        3 -> sourceStrip
                        4 -> colorBuffer.also {
                            Imgproc.cvtColor(sourceStrip, it, Imgproc.COLOR_BGRA2BGR)
                        }
                        else -> error("Unsupported channel count: ${source.channels()}")
                    }

                    val backgroundStrip = stripScope.own(background.submat(rect))
                    Core.inRange(
                        backgroundStrip,
                        Scalar(0.0),
                        Scalar((MIN_BACKGROUND_LEVEL - 1).toDouble()),
                        lowBackgroundMask,
                    )
                    backgroundStrip.copyTo(safeBackground)
                    safeBackground.setTo(Scalar(255.0), lowBackgroundMask)
                    Imgproc.cvtColor(safeBackground, colorBackground, Imgproc.COLOR_GRAY2BGR)

                    Core.divide(colorStrip, colorBackground, normalized, 255.0)
                    normalized.convertTo(contrast, -1, parameters.alpha, parameters.beta)

                    // Only make pixels white when all BGR channels are bright. This protects
                    // colored signatures and stamps from a luminance-only threshold.
                    val threshold = parameters.whiteThreshold
                    Core.inRange(
                        contrast,
                        Scalar(threshold, threshold, threshold, threshold),
                        Scalar(255.0, 255.0, 255.0, 255.0),
                        whiteMask,
                    )
                    contrast.setTo(Scalar(255.0, 255.0, 255.0, 255.0), whiteMask)

                    val outputStrip = stripScope.own(output.submat(rect))
                    when (source.channels()) {
                        1 -> Imgproc.cvtColor(contrast, outputStrip, Imgproc.COLOR_BGR2GRAY)
                        3 -> contrast.copyTo(outputStrip)
                        4 -> {
                            val alphaStrip = stripScope.own(Mat())
                            Core.extractChannel(sourceStrip, alphaStrip, 3)
                            val channels = mutableListOf<Mat>()
                            Core.split(contrast, channels)
                            channels.forEach(stripScope::own)
                            channels += alphaStrip
                            Core.merge(channels, outputStrip)
                        }
                    }
                } finally {
                    stripScope.close()
                }
                top += height
            }
            scope.release(background)
            return output
        } finally {
            bufferScope.close()
        }
    }

    /** Instrumentation hook so native Mat ownership can be stress-tested without file codecs. */
    internal suspend fun enhanceMatForTesting(
        source: Mat,
        strength: EnhancementStrength,
        scope: MatScope,
    ): Mat = enhanceMat(source, strength, scope)

    private fun strengthParameters(strength: EnhancementStrength): EnhancementParameters = when (strength) {
        EnhancementStrength.Light -> EnhancementParameters(alpha = 1.0, beta = 0.0, whiteThreshold = 210.0)
        EnhancementStrength.Normal -> EnhancementParameters(alpha = 1.05, beta = -10.0, whiteThreshold = 200.0)
        EnhancementStrength.Strong -> EnhancementParameters(alpha = 1.15, beta = -20.0, whiteThreshold = 185.0)
    }

    /** JVM-only reference pipeline retained for algorithm fixtures; production does not call it. */
    internal fun processPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: EnhancementStrength = EnhancementStrength.Normal,
    ): IntArray {
        if (pixels.isEmpty() || width <= 0 || height <= 0 || pixels.size < width * height) return pixels

        val parameters = strengthParameters(strength)
        val gray = IntArray(pixels.size) { luminance(pixels[it]) }
        val background = downsampledBackground(gray, width, height)
        return IntArray(pixels.size) { i ->
            val safeBackground = if (background[i] < MIN_BACKGROUND_LEVEL) {
                255f
            } else {
                background[i].toFloat()
            }
            val r = normalizeChannel((pixels[i] shr 16) and 0xFF, safeBackground, parameters)
            val g = normalizeChannel((pixels[i] shr 8) and 0xFF, safeBackground, parameters)
            val b = normalizeChannel(pixels[i] and 0xFF, safeBackground, parameters)
            val a = (pixels[i] shr 24) and 0xFF

            if (r >= parameters.whiteThreshold && g >= parameters.whiteThreshold && b >= parameters.whiteThreshold) {
                (a shl 24) or (255 shl 16) or (255 shl 8) or 255
            } else {
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun normalizeChannel(value: Int, background: Float, parameters: EnhancementParameters): Int =
        min(255.0, max(0.0, parameters.alpha * (value * 255.0 / background) + parameters.beta))
            .roundToInt()

    private fun inspect(file: File): EnhancementInput {
        if (!file.isFile || file.length() <= 0L) return EnhancementInput.Invalid
        val format = readFormat(file) ?: return EnhancementInput.Unsupported
        if (format == DetectedFileFormat.Pdf) return EnhancementInput.Pdf

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return EnhancementInput.Invalid
        val imageFormat = when (format) {
            DetectedFileFormat.Jpeg -> EnhancementImageFormat.Jpeg
            DetectedFileFormat.Png -> EnhancementImageFormat.Png
            DetectedFileFormat.Pdf -> error("PDF handled before image inspection")
        }
        return EnhancementInput.Image(imageFormat, options.outWidth, options.outHeight)
    }

    private fun readFormat(file: File): DetectedFileFormat? = try {
        val signature = ByteArray(8)
        FileInputStream(file).use { input ->
            val count = input.read(signature)
            when {
                count >= 4 && signature[0] == '%'.code.toByte() && signature[1] == 'P'.code.toByte() &&
                    signature[2] == 'D'.code.toByte() && signature[3] == 'F'.code.toByte() -> DetectedFileFormat.Pdf
                count >= 8 && signature.contentEquals(byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                )) -> DetectedFileFormat.Png
                count >= 2 && signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() -> DetectedFileFormat.Jpeg
                else -> null
            }
        }
    } catch (_: IOException) {
        null
    }

    private fun isValidEncodedImage(
        file: File,
        expected: EnhancementImageFormat,
        width: Int,
        height: Int,
    ): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val inspection = inspect(file) as? EnhancementInput.Image ?: return false
        return inspection.format == expected && inspection.width == width && inspection.height == height
    }

    private fun downsampledBackground(gray: IntArray, width: Int, height: Int): IntArray {
        val smallW = max(1, width / DOWNSAMPLE_FACTOR)
        val smallH = max(1, height / DOWNSAMPLE_FACTOR)
        val small = downsample(gray, width, height, smallW, smallH)
        val smallRadius = (max(smallW, smallH) / 16).coerceIn(3, 16)
        val smallBlurred = boxBlur(small, smallW, smallH, smallRadius)
        return upscale(smallBlurred, smallW, smallH, width, height)
    }

    private fun downsample(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            val sy0 = (dy * yRatio).toInt()
            val sy1 = min(((dy + 1) * yRatio).toInt(), srcH)
            for (dx in 0 until dstW) {
                val sx0 = (dx * xRatio).toInt()
                val sx1 = min(((dx + 1) * xRatio).toInt(), srcW)
                var sum = 0
                var count = 0
                for (sy in sy0 until sy1) {
                    for (sx in sx0 until sx1) {
                        sum += src[sy * srcW + sx]
                        count++
                    }
                }
                dst[dy * dstW + dx] = if (count > 0) sum / count else 0
            }
        }
        return dst
    }

    private fun upscale(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        val dst = IntArray(dstW * dstH)
        val xRatio = (srcW - 1).toFloat() / max(1, dstW - 1)
        val yRatio = (srcH - 1).toFloat() / max(1, dstH - 1)
        for (dy in 0 until dstH) {
            val sy = dy * yRatio
            val sy0 = sy.toInt().coerceIn(0, srcH - 1)
            val sy1 = (sy0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - sy0
            for (dx in 0 until dstW) {
                val sx = dx * xRatio
                val sx0 = sx.toInt().coerceIn(0, srcW - 1)
                val sx1 = (sx0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - sx0
                val v00 = src[sy0 * srcW + sx0]
                val v01 = src[sy0 * srcW + sx1]
                val v10 = src[sy1 * srcW + sx0]
                val v11 = src[sy1 * srcW + sx1]
                val top = v00 + (v01 - v00) * fx
                val bottom = v10 + (v11 - v10) * fx
                dst[dy * dstW + dx] = (top + (bottom - top) * fy).toInt()
            }
        }
        return dst
    }

    private fun boxBlur(data: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(data.size)
        val result = IntArray(data.size)
        val kernel = radius * 2 + 1
        for (y in 0 until height) {
            var sum = 0
            for (x in -radius..radius) sum += data[y * width + min(max(x, 0), width - 1)]
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / kernel
                val leftX = min(max(x - radius, 0), width - 1)
                val rightX = min(max(x + radius + 1, 0), width - 1)
                sum += data[y * width + rightX] - data[y * width + leftX]
            }
        }
        for (x in 0 until width) {
            var sum = 0
            for (y in -radius..radius) sum += horizontal[min(max(y, 0), height - 1) * width + x]
            for (y in 0 until height) {
                result[y * width + x] = sum / kernel
                val topY = min(max(y - radius, 0), height - 1)
                val bottomY = min(max(y + radius + 1, 0), height - 1)
                sum += horizontal[bottomY * width + x] - horizontal[topY * width + x]
            }
        }
        return result
    }

    internal fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
    }

    private data class EnhancementParameters(
        val alpha: Double,
        val beta: Double,
        val whiteThreshold: Double,
    )

    private enum class DetectedFileFormat {
        Jpeg,
        Png,
        Pdf,
    }
}

internal object AtomicFileReplacer {
    @Throws(IOException::class)
    fun replace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic replace is not supported on this filesystem", error)
        }
    }
}
