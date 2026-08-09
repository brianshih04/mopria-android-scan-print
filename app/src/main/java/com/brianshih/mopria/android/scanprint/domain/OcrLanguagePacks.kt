package com.brianshih.mopria.android.scanprint.domain

import android.content.Context
import androidx.annotation.StringRes
import com.brianshih.mopria.android.scanprint.R
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Script model families supplied by ML Kit Text Recognition v2. */
enum class OcrLanguageModel {
    Latin,
    Chinese,
    Japanese,
    Korean,
    Unsupported,
}

/** Regions used to keep the language picker understandable as the catalog grows. */
enum class OcrLanguageRegion(@StringRes val labelRes: Int) {
    Global(R.string.ocr_region_global),
    EastAsia(R.string.ocr_region_east_asia),
    EuropeAmericas(R.string.ocr_region_europe_americas),
}

/** User-facing languages mapped to ML Kit's script-level model clients. */
enum class OcrLanguagePack(
    @StringRes val labelRes: Int,
    val region: OcrLanguageRegion,
    val defaultSelected: Boolean,
    val model: OcrLanguageModel,
) {
    English(
        labelRes = R.string.ocr_language_english,
        region = OcrLanguageRegion.Global,
        defaultSelected = true,
        model = OcrLanguageModel.Latin,
    ),
    TraditionalChinese(
        labelRes = R.string.ocr_language_traditional_chinese,
        region = OcrLanguageRegion.EastAsia,
        defaultSelected = true,
        model = OcrLanguageModel.Chinese,
    ),
    SimplifiedChinese(
        labelRes = R.string.ocr_language_simplified_chinese,
        region = OcrLanguageRegion.EastAsia,
        defaultSelected = true,
        model = OcrLanguageModel.Chinese,
    ),
    Japanese(
        labelRes = R.string.ocr_language_japanese,
        region = OcrLanguageRegion.EastAsia,
        defaultSelected = false,
        model = OcrLanguageModel.Japanese,
    ),
    Korean(
        labelRes = R.string.ocr_language_korean,
        region = OcrLanguageRegion.EastAsia,
        defaultSelected = false,
        model = OcrLanguageModel.Korean,
    ),
    Spanish(
        labelRes = R.string.ocr_language_spanish,
        region = OcrLanguageRegion.EuropeAmericas,
        defaultSelected = false,
        model = OcrLanguageModel.Latin,
    ),
    Portuguese(
        labelRes = R.string.ocr_language_portuguese,
        region = OcrLanguageRegion.EuropeAmericas,
        defaultSelected = false,
        model = OcrLanguageModel.Latin,
    ),
    German(
        labelRes = R.string.ocr_language_german,
        region = OcrLanguageRegion.EuropeAmericas,
        defaultSelected = false,
        model = OcrLanguageModel.Latin,
    ),
    French(
        labelRes = R.string.ocr_language_french,
        region = OcrLanguageRegion.EuropeAmericas,
        defaultSelected = false,
        model = OcrLanguageModel.Latin,
    ),
    Russian(
        labelRes = R.string.ocr_language_russian,
        region = OcrLanguageRegion.EuropeAmericas,
        defaultSelected = false,
        model = OcrLanguageModel.Unsupported,
    ),
    ;

    companion object {
        val defaultSelection: Set<OcrLanguagePack> = entries
            .filter { it.defaultSelected }
            .toSet()

        fun fromStoredValue(value: String?): OcrLanguagePack? = value
            ?.let { stored -> entries.firstOrNull { it.name == stored } }
    }
}

enum class OcrLanguagePackStatus(@StringRes val labelRes: Int) {
    ManagedByMlKit(R.string.ocr_pack_status_included),
    Available(R.string.ocr_pack_status_available),
    Downloading(R.string.ocr_pack_status_downloading),
    Ready(R.string.ocr_pack_status_installed),
    Failed(R.string.ocr_pack_status_failed),
    Unsupported(R.string.ocr_pack_status_conversion_pending),
}

data class OcrLanguagePackState(
    val language: OcrLanguagePack,
    val selected: Boolean,
    val active: Boolean,
    val status: OcrLanguagePackStatus,
    val progress: Int = 0,
)

sealed interface OcrPackDownloadResult {
    data class Ready(val language: OcrLanguagePack) : OcrPackDownloadResult

    data class Requested(val language: OcrLanguagePack) : OcrPackDownloadResult

    data class Failed(val language: OcrLanguagePack) : OcrPackDownloadResult
}

/**
 * Coordinates explicit ML Kit script-model requests. The model bytes stay under Google Play
 * services; this class deliberately has no URL, archive, checksum, or app-private model store.
 */
class OcrLanguagePackManager(context: Context) {
    private val appContext = context.applicationContext
    private val moduleInstallClient = ModuleInstall.getClient(appContext)

    fun states(
        selected: Set<OcrLanguagePack>,
        active: OcrLanguagePack,
        statusOverrides: Map<OcrLanguagePack, OcrLanguagePackStatus> = emptyMap(),
    ): List<OcrLanguagePackState> {
        val safeSelection = selected
            .filterTo(linkedSetOf()) { it.model != OcrLanguageModel.Unsupported }
            .ifEmpty { OcrLanguagePack.defaultSelection }
        return OcrLanguagePack.entries.map { language ->
            OcrLanguagePackState(
                language = language,
                selected = language in safeSelection,
                active = language == active,
                status = statusOverrides[language] ?: when {
                    language.model == OcrLanguageModel.Unsupported -> OcrLanguagePackStatus.Unsupported
                    OcrDownloadableFontPack.forLanguage(language)
                        ?.isInstalled(appContext) == false -> OcrLanguagePackStatus.Available
                    else -> OcrLanguagePackStatus.ManagedByMlKit
                },
            )
        }
    }

    suspend fun download(
        language: OcrLanguagePack,
        onProgress: (Int) -> Unit = {},
    ): OcrPackDownloadResult = withContext(Dispatchers.IO) {
        if (language.model == OcrLanguageModel.Unsupported) {
            return@withContext OcrPackDownloadResult.Failed(language)
        }

        val fontPack = OcrDownloadableFontPack.forLanguage(language)
        if (fontPack != null) {
            val fontReady = OcrFontPackDownloader.ensureAvailable(appContext, fontPack) { progress ->
                onProgress((progress * 60) / 100)
            }
            if (!fontReady) {
                return@withContext OcrPackDownloadResult.Failed(language)
            }
        }

        val recognizer = try {
            MlKitOcrRecognizerFactory.create(language)
        } catch (_: Exception) {
            return@withContext OcrPackDownloadResult.Failed(language)
        }

        try {
            val modules = awaitMlKitTask(
                moduleInstallClient.areModulesAvailable(recognizer),
            )
            if (modules.areModulesAvailable()) {
                onProgress(100)
                return@withContext OcrPackDownloadResult.Ready(language)
            }

            onProgress(if (fontPack == null) 10 else 70)
            val response = awaitMlKitTask(
                moduleInstallClient.installModules(
                    ModuleInstallRequest.Builder()
                        .addApi(recognizer)
                        .build(),
                ),
            )
            onProgress(100)
            if (response.areModulesAlreadyInstalled()) {
                OcrPackDownloadResult.Ready(language)
            } else {
                OcrPackDownloadResult.Requested(language)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OcrPackDownloadResult.Failed(language)
        } finally {
            recognizer.close()
        }
    }
}
