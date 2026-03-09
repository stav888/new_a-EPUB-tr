package com.example.epubtranslator.translation

import com.example.epubtranslator.R

/**
 * Enum representing different translation methods available in the app
 */
enum class TranslationMethod(
    val displayChar: String,
    val displayNameResId: Int,
    val description: String
) {
    DEFAULT("D", R.string.translation_method_default, "Default API-based translation"),
    GOOGLE_INTENT("G", R.string.translation_method_google, "Google Translate app integration"),
    YANDEX_INTENT("Y", R.string.translation_method_yandex, "Yandex Translate app integration"),
    GOOGLE_API("G2", R.string.translation_method_google_api, "Google Translate API direct"),
    YANDEX_API("Y2", R.string.translation_method_yandex_api, "Yandex Translate API direct");

    companion object {
        /**
         * Get the default translation method
         */
        fun getDefault(): TranslationMethod = DEFAULT

        /**
         * Get translation method by display character
         */
        fun fromDisplayChar(char: String): TranslationMethod {
            return values().find { it.displayChar == char } ?: getDefault()
        }

        /**
         * Get translation method by name
         */
        fun fromString(name: String): TranslationMethod {
            return try {
                valueOf(name)
            } catch (e: IllegalArgumentException) {
                getDefault()
            }
        }
    }
}
