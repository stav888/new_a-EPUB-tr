package com.example.epubtranslator.translation

import com.example.epubtranslator.R

/**
 * Enum representing the available translation APIs
 */
enum class TranslationApi(val displayNameResId: Int) {
    GOOGLE(R.string.google_api),
    YANDEX(R.string.yandex_api);

    companion object {
        /**
         * Get the default translation API
         */
        fun getDefault(): TranslationApi = GOOGLE

        /**
         * Get the translation API by name
         */
        fun fromString(name: String): TranslationApi {
            return try {
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                getDefault()
            }
        }
    }
}
