package com.example.epubtranslator.translation

import com.example.epubtranslator.R

/** The only in-app translation engine. */
enum class TranslationApi(val displayNameResId: Int) {
    ML_KIT(R.string.ml_kit_translation);

    companion object {
        /**
         * Get the default translation API
         */
        fun getDefault(): TranslationApi = ML_KIT

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
