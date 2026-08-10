package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Result of running the optional on-device OCR backend for one scanned image. */
sealed interface OcrResult {
    data class Applied(
        val text: String,
        val layout: OcrTextLayout = OcrTextLayout(),
    ) : OcrResult

    data class Skipped(val reason: OcrSkipReason) : OcrResult

    data class Failed(val error: OcrError) : OcrResult
}

/** True only when the OCR result contains text that can be placed at a real page position. */
internal fun OcrResult?.hasPositionedText(): Boolean = (this as? OcrResult.Applied)
    ?.layout
    ?.blocks
    ?.asSequence()
    ?.flatMap { block -> block.lines.asSequence() }
    ?.any { line ->
        line.text.isNotBlank() && line.bounds?.let { it.width > 0 && it.height > 0 } == true
    } == true

enum class OcrSkipReason {
    ModelsMissing,
    LanguagePackUnavailable,
    RuntimeUnavailable,
    UnsupportedFormat,
    ImageTooLarge,
}

enum class OcrError {
    InvalidSource,
    InferenceFailed,
}

fun interface OcrEngine {
    suspend fun recognize(imageFile: File): OcrResult
}

/** Optional extension used when a caller wants a specific ML Kit script. */
interface LanguageAwareOcrEngine : OcrEngine {
    suspend fun recognize(imageFile: File, language: OcrLanguagePack): OcrResult
}

/** Hard decode limits keep one OCR bitmap below roughly 48 MiB in ARGB_8888. */
const val MAX_OCR_PIXELS = 12_000_000L
const val MAX_OCR_LONG_EDGE = 4_096

/** Pure sizing policy shared by the production decoder and unit tests. */
internal object OcrImageSizing {
    fun sampleSize(
        width: Int,
        height: Int,
        maximumPixels: Long = MAX_OCR_PIXELS,
        maximumLongEdge: Int = MAX_OCR_LONG_EDGE,
    ): Int? {
        if (width <= 0 || height <= 0 || maximumPixels <= 0L || maximumLongEdge <= 0) return null
        var sampleSize = 1
        while (!withinLimits(width, height, sampleSize, maximumPixels, maximumLongEdge)) {
            if (sampleSize > 1 shl 20) return null
            sampleSize *= 2
        }
        return sampleSize
    }

    fun withinLimits(
        width: Int,
        height: Int,
        sampleSize: Int,
        maximumPixels: Long = MAX_OCR_PIXELS,
        maximumLongEdge: Int = MAX_OCR_LONG_EDGE,
    ): Boolean {
        if (width <= 0 || height <= 0 || sampleSize <= 0) return false
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        return sampledWidth <= maximumLongEdge &&
            sampledHeight <= maximumLongEdge &&
            sampledWidth * sampledHeight <= maximumPixels
    }
}

private sealed interface OcrBitmapLoadResult {
    data class Loaded(val bitmap: Bitmap) : OcrBitmapLoadResult
    data object UnsupportedFormat : OcrBitmapLoadResult
    data object ImageTooLarge : OcrBitmapLoadResult
}

private object OcrBitmapLoader {
    fun load(imageFile: File): OcrBitmapLoadResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val sampleSize = OcrImageSizing.sampleSize(bounds.outWidth, bounds.outHeight)
            ?: return OcrBitmapLoadResult.UnsupportedFormat

        return try {
            val bitmap = BitmapFactory.decodeFile(
                imageFile.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: return OcrBitmapLoadResult.UnsupportedFormat
            if (!OcrImageSizing.withinLimits(bitmap.width, bitmap.height, 1)) {
                bitmap.recycle()
                OcrBitmapLoadResult.ImageTooLarge
            } else {
                OcrBitmapLoadResult.Loaded(bitmap)
            }
        } catch (_: OutOfMemoryError) {
            OcrBitmapLoadResult.ImageTooLarge
        }
    }
}

/** Creates the unbundled ML Kit recognizer matching the selected script model. */
object MlKitOcrRecognizerFactory {
    fun create(language: OcrLanguagePack): TextRecognizer = when (language.model) {
        OcrLanguageModel.Latin ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrLanguageModel.Chinese ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrLanguageModel.Japanese ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrLanguageModel.Korean ->
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        OcrLanguageModel.Unsupported ->
            error("The selected language is not supported by ML Kit Text Recognition v2")
    }
}

/**
 * Google ML Kit Text Recognition v2 backend. The source stays file-first until a bounded, sampled
 * bitmap is decoded for inference. Unbundled script models are requested through Google Play
 * services rather than app-hosted files.
 */
class MlKitOcrEngine(
    context: Context,
) : LanguageAwareOcrEngine {
    companion object {
        fun production(context: Context): MlKitOcrEngine = MlKitOcrEngine(context)
    }

    private val appContext = context.applicationContext
    private val moduleInstallClient = ModuleInstall.getClient(appContext)

    override suspend fun recognize(imageFile: File): OcrResult =
        recognize(imageFile, OcrLanguagePack.SimplifiedChinese)

    override suspend fun recognize(
        imageFile: File,
        language: OcrLanguagePack,
    ): OcrResult = withContext(Dispatchers.IO) {
        if (!imageFile.isFile || imageFile.length() <= 0L) {
            return@withContext OcrResult.Failed(OcrError.InvalidSource)
        }

        val recognizer = try {
            MlKitOcrRecognizerFactory.create(language)
        } catch (_: Exception) {
            return@withContext OcrResult.Skipped(OcrSkipReason.RuntimeUnavailable)
        }
        var closeRecognizerOnExit = true

        try {
            val modules = awaitMlKitTask(moduleInstallClient.areModulesAvailable(recognizer))
            if (!modules.areModulesAvailable()) {
                runCatching {
                    awaitMlKitTask(
                        moduleInstallClient.installModules(
                            ModuleInstallRequest.Builder()
                                .addApi(recognizer)
                                .build(),
                        ),
                    )
                }
                return@withContext OcrResult.Skipped(OcrSkipReason.ModelsMissing)
            }

            val bitmap = when (val loaded = OcrBitmapLoader.load(imageFile)) {
                is OcrBitmapLoadResult.Loaded -> loaded.bitmap
                OcrBitmapLoadResult.UnsupportedFormat ->
                    return@withContext OcrResult.Skipped(OcrSkipReason.UnsupportedFormat)
                OcrBitmapLoadResult.ImageTooLarge ->
                    return@withContext OcrResult.Skipped(OcrSkipReason.ImageTooLarge)
            }
            var recycleBitmapOnExit = true
            try {
                // Rotation stays zero so ML Kit coordinates use the same decoded pixel space as
                // DocumentPageBitmapLoader and the searchable-PDF transform.
                val image = InputImage.fromBitmap(bitmap, 0)
                val inferenceTask = recognizer.process(image)
                val result = try {
                    awaitMlKitTask(inferenceTask)
                } catch (error: CancellationException) {
                    // Coroutine cancellation does not guarantee cancellation of the Google Task.
                    // Keep its input and recognizer alive until native inference actually finishes.
                    recycleBitmapOnExit = false
                    closeRecognizerOnExit = false
                    inferenceTask.addOnCompleteListener {
                        bitmap.recycle()
                        recognizer.close()
                    }
                    throw error
                }
                val layout = result.toOcrTextLayout(bitmap.width, bitmap.height)
                OcrResult.Applied(
                    text = OcrTextFormatter.format(layout, result.text),
                    layout = layout,
                )
            } finally {
                if (recycleBitmapOnExit) bitmap.recycle()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MlKitException) {
            OcrResult.Skipped(OcrSkipReason.RuntimeUnavailable)
        } catch (_: UnsatisfiedLinkError) {
            OcrResult.Skipped(OcrSkipReason.RuntimeUnavailable)
        } catch (_: Exception) {
            OcrResult.Failed(OcrError.InferenceFailed)
        } finally {
            if (closeRecognizerOnExit) recognizer.close()
        }
    }
}

private fun com.google.mlkit.vision.text.Text.toOcrTextLayout(
    imageWidth: Int,
    imageHeight: Int,
): OcrTextLayout = OcrTextLayout(
        blocks = textBlocks.map { it.toOcrTextBlock() },
        imageWidth = imageWidth.coerceAtLeast(0),
        imageHeight = imageHeight.coerceAtLeast(0),
    )

private fun com.google.mlkit.vision.text.Text.TextBlock.toOcrTextBlock(): OcrTextBlock = OcrTextBlock(
    text = text,
    bounds = boundingBox.toOcrBounds(),
    cornerPoints = cornerPoints.toOcrPoints(),
    recognizedLanguage = recognizedLanguage,
    lines = lines.map { it.toOcrTextLine() },
)

private fun com.google.mlkit.vision.text.Text.Line.toOcrTextLine(): OcrTextLine = OcrTextLine(
    text = text,
    bounds = boundingBox.toOcrBounds(),
    cornerPoints = cornerPoints.toOcrPoints(),
    angle = angle,
    confidence = confidence.toOcrConfidence(),
    recognizedLanguage = recognizedLanguage,
    elements = elements.map { it.toOcrTextElement() },
)

private fun com.google.mlkit.vision.text.Text.Element.toOcrTextElement(): OcrTextElement = OcrTextElement(
    text = text,
    bounds = boundingBox.toOcrBounds(),
    cornerPoints = cornerPoints.toOcrPoints(),
    angle = angle,
    confidence = confidence.toOcrConfidence(),
    recognizedLanguage = recognizedLanguage,
    symbols = symbols.map { it.toOcrTextSymbol() },
)

private fun com.google.mlkit.vision.text.Text.Symbol.toOcrTextSymbol(): OcrTextSymbol = OcrTextSymbol(
    text = text,
    bounds = boundingBox.toOcrBounds(),
    cornerPoints = cornerPoints.toOcrPoints(),
    angle = angle,
    confidence = confidence.toOcrConfidence(),
    recognizedLanguage = recognizedLanguage,
)

private fun Rect?.toOcrBounds(): OcrBounds? = this?.let { value ->
    OcrBounds(value.left, value.top, value.right, value.bottom)
}

private fun Array<Point>?.toOcrPoints(): List<OcrPoint> = orEmpty().map { point ->
    OcrPoint(point.x, point.y)
}

private fun Float.toOcrConfidence(): Float? = takeIf { it.isFinite() && it > 0f }

/** Small coroutine bridge kept local so ML Kit Tasks never block the scan dispatcher. */
internal suspend fun <T> awaitMlKitTask(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
    task.addOnCompleteListener { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            completed.isCanceled -> continuation.cancel(CancellationException("ML Kit task cancelled"))
            completed.isSuccessful -> continuation.resume(completed.result)
            else -> continuation.resumeWithException(
                completed.exception ?: IllegalStateException("ML Kit task failed"),
            )
        }
    }
}
