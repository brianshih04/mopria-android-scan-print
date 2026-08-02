package com.brianshih.mopria.android.scanprint.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.core.os.ConfigurationCompat
import androidx.core.content.edit
import com.brianshih.mopria.android.scanprint.R
import java.util.Locale

/** Languages supported by the app. System means follow Android's locale settings. */
internal enum class AppLanguage(
    val tag: String,
    @StringRes val labelRes: Int,
) {
    System("", R.string.language_system),
    English("en", R.string.language_english),
    Japanese("ja", R.string.language_japanese),
    Korean("ko", R.string.language_korean),
    Spanish("es", R.string.language_spanish),
    Portuguese("pt", R.string.language_portuguese),
    German("de", R.string.language_german),
    French("fr", R.string.language_french),
    Russian("ru", R.string.language_russian),
    TraditionalChinese("zh-TW", R.string.language_traditional_chinese),
    SimplifiedChinese("zh-CN", R.string.language_simplified_chinese),
    ;

    companion object {
        val userSelectable: List<AppLanguage> = entries

        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

internal object LanguageManager {
    private const val PREFERENCES_NAME = "mopria_settings"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun currentOverride(context: Context): AppLanguage = AppLanguage.fromTag(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null),
    )

    fun setOverride(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit {
            if (language == AppLanguage.System) remove(KEY_LANGUAGE_TAG)
            else putString(KEY_LANGUAGE_TAG, language.tag)
        }
    }

    /** Wraps the activity base context so Compose and Android resources use the same locale. */
    fun wrap(base: Context): Context {
        val language = resolve(base)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.tag))
        return base.createConfigurationContext(configuration)
    }

    internal fun resolve(context: Context): AppLanguage {
        val override = currentOverride(context)
        if (override != AppLanguage.System) return override

        val systemLocales = ConfigurationCompat.getLocales(context.resources.configuration)
        return (0 until systemLocales.size())
            .asSequence()
            .mapNotNull { index -> systemLocales[index]?.let(::languageFor) }
            .firstOrNull { it != AppLanguage.System }
            ?: AppLanguage.English
    }

    internal fun languageFor(locale: Locale): AppLanguage? = when {
        locale.language == "zh" && (
            locale.script.equals("Hant", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
            ) -> AppLanguage.TraditionalChinese
        locale.language == "zh" && (
            locale.script.equals("Hans", ignoreCase = true) ||
                locale.country.uppercase(Locale.ROOT) in setOf("CN", "SG", "MY")
            ) -> AppLanguage.SimplifiedChinese
        else -> AppLanguage.entries.firstOrNull { it.tag == locale.language }
    }
}
