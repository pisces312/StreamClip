package com.pisces312.streamclip.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "language"
    const val FOLLOW_SYSTEM = "system"
    const val LANGUAGE_ZH = "zh"
    const val LANGUAGE_EN = "en"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM
    }

    fun setLanguage(context: Context, language: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, language).apply()

        // Android 13+ 使用 per-app language API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeList = when (language) {
                LANGUAGE_ZH -> LocaleListCompat.forLanguageTags("zh-CN")
                LANGUAGE_EN -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }

    fun applyLanguage(context: Context): Context {
        val language = getLanguage(context)
        return when (language) {
            LANGUAGE_ZH -> updateResources(context, Locale.SIMPLIFIED_CHINESE)
            LANGUAGE_EN -> updateResources(context, Locale.ENGLISH)
            else -> context // Follow system, no change
        }
    }

    @Suppress("DEPRECATION")
    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun wrapContext(context: Context): ContextThemeWrapper {
        return ContextThemeWrapper(applyLanguage(context), context.theme)
    }
}
