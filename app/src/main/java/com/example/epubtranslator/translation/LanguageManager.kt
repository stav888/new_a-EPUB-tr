package com.example.epubtranslator.translation

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages language preferences for translation
 */
class LanguageManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "translation_prefs"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val DEFAULT_LANGUAGE_CODE = "he" // Hebrew as default
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get the currently selected target language for translation
     */
    fun getTargetLanguage(): Language {
        val languageCode = prefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_LANGUAGE_CODE) ?: DEFAULT_LANGUAGE_CODE
        return Language.fromCode(languageCode)
    }

    /**
     * Set the target language for translation
     */
    fun setTargetLanguage(language: Language) {
        prefs.edit().putString(KEY_TARGET_LANGUAGE, language.code).apply()
    }
}
