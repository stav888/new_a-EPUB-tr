package com.example.epubtranslator.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager class that coordinates translation operations
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"
    }

    private val translationService = TranslationService()
    private val languageManager = LanguageManager(context)

    // Shared preferences keys
    private val PREF_TRANSLATION_API = "translation_api"
    private val PREF_TRANSLATION_METHOD = "translation_method"

    /**
     * Set the translation API to use
     */
    fun setTranslationApi(api: TranslationApi) {
        translationService.setTranslationApi(api)

        // Save the selection to preferences
        val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_TRANSLATION_API, api.name).apply()

        Log.d(TAG, "Translation API set to: $api")
    }

    /**
     * Get the current translation API
     */
    fun getCurrentApi(): TranslationApi {
        // Get the saved API from preferences, or use the default
        val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        val apiName = prefs.getString(PREF_TRANSLATION_API, TranslationApi.getDefault().name)

        // Convert the name to an enum value
        val api = TranslationApi.fromString(apiName ?: TranslationApi.getDefault().name)

        // Make sure the service is using the same API
        translationService.setTranslationApi(api)

        return api
    }

    /**
     * Set the translation method to use
     */
    fun setTranslationMethod(method: TranslationMethod) {
        val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_TRANSLATION_METHOD, method.name).apply()
        Log.d(TAG, "Translation method set to: $method")
    }

    /**
     * Get the current translation method
     */
    fun getCurrentMethod(): TranslationMethod {
        val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        val methodName = prefs.getString(PREF_TRANSLATION_METHOD, TranslationMethod.getDefault().name)
        return TranslationMethod.fromString(methodName ?: TranslationMethod.getDefault().name)
    }

    /**
     * Translate the given text to the target language set in preferences
     * or automatically determine the target language based on the source language
     */
    suspend fun translateText(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Get the target language from preferences
            val targetLanguage = languageManager.getTargetLanguage()

            // Log the target language for debugging
            Log.d(TAG, "Preferred target language from settings: ${targetLanguage.displayName} (${targetLanguage.code})")

            // Use the target language from settings
            // The translation service will still detect the source language
            val result = translationService.translateText(text, targetLanguage.code)

            // Log the result for debugging
            result.fold(
                onSuccess = { translatedText ->
                    android.util.Log.d("TranslationManager", "Translation successful: ${translatedText.take(50)}...")
                },
                onFailure = { error ->
                    android.util.Log.e("TranslationManager", "Translation failed: ${error.message}")
                }
            )

            result
        } catch (e: Exception) {
            android.util.Log.e("TranslationManager", "Exception in translateText", e)
            Result.failure(e)
        }
    }

    /**
     * Get the current target language
     */
    fun getTargetLanguage(): Language {
        return languageManager.getTargetLanguage()
    }


    /**
     * Expose language detection for UI logic (e.g., block same-language translation)
     */
    fun detectLanguage(text: String): String {
        return translationService.detectLanguagePublic(text)
    }

    /**
     * Simple heuristic to check if text is likely in the given language
     */
    private fun isTextInLanguage(text: String, languageCode: String): Boolean {
        // This is a very simple heuristic and not reliable for all languages
        // For a production app, you would use a language detection library

        // For Hebrew
        if (languageCode == "he") {
            // Check if text contains Hebrew characters
            return text.any { char -> char.code in 0x0590..0x05FF || char.code in 0xFB1D..0xFB4F }
        }

        // For English
        if (languageCode == "en") {
            // Check if text contains mostly Latin characters and common English words
            val commonEnglishWords = listOf("the", "and", "is", "in", "to", "it", "that", "was", "for")
            val words = text.lowercase().split(Regex("\\s+"))
            val englishWordCount = words.count { it in commonEnglishWords }

            // If we find several common English words, it's likely English
            return englishWordCount >= 2
        }

        // For other languages, we can't easily detect
        // Just return false to allow translation
        return false
    }
}
