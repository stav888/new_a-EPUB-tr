package com.example.epubtranslator.translation

import com.example.epubtranslator.R

/** In-app paragraph translation is always performed by Google ML Kit on-device. */
enum class TranslationMethod(
    val displayChar: String,
    val displayNameResId: Int,
    val description: String
) {
    DEFAULT("ML", R.string.translation_method_ml_kit, "Google ML Kit on-device translation");

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
