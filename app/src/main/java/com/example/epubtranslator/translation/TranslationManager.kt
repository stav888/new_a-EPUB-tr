package com.example.epubtranslator.translation

import android.content.Context
import android.util.Log
import com.example.epubtranslator.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exception thrown when offline models are not downloaded
 */
class ModelNotDownloadedException(
    message: String,
    val sourceLanguage: Language,
    val targetLanguage: Language
) : Exception(message)

/**
 * Manager class that coordinates translation operations
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"
    }

    private val translationService = TranslationService()
    private val mlKitService = MlKitTranslationService()
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
            val targetLanguage = languageManager.getTargetLanguage()
            Log.d(TAG, "Preferred target language from settings: ${targetLanguage.displayName} (${targetLanguage.code})")

            // Translation services can mis-detect or reject list markers as the whole
            // paragraph. Translate the line contents and restore the markers afterward.
            val bulletPattern = Regex("^([\\s]*[*•▪◦‣-][\\s]*)")
            val lines = text.split("\\n")
            val bulletPrefixes = lines.map { line -> bulletPattern.find(line)?.value ?: "" }
            val hasBulletMarkers = bulletPrefixes.any { it.isNotEmpty() }
            val translationInput = if (hasBulletMarkers) {
                lines.joinToString("\\n") { line -> bulletPattern.replaceFirst(line, "").trim() }
            } else {
                text
            }

            fun restoreBulletMarkers(translatedText: String): String {
                if (!hasBulletMarkers) return translatedText
                val translatedLines = translatedText.split("\\n").toMutableList()
                return translatedLines.mapIndexed { index, line ->
                    val prefix = bulletPrefixes.getOrNull(index).orEmpty()
                    if (prefix.isNotEmpty()) prefix + line.trimStart() else line
                }.joinToString("\\n")
            }

            // Check if online
            val isOnline = NetworkMonitor.isOnline(context)
            Log.d(TAG, "Network status: ${if (isOnline) "ONLINE" else "OFFLINE"}")

            if (isOnline) {
                // Use online translation
                val result = translationService.translateText(translationInput, targetLanguage.code)
                result.map { translatedText -> restoreBulletMarkers(translatedText) }.also { translatedResult ->
                    translatedResult.fold(
                    onSuccess = { translatedText ->
                        Log.d("TranslationManager", "Online translation successful: ${translatedText.take(50)}...")
                    },
                    onFailure = { error ->
                        Log.e("TranslationManager", "Online translation failed: ${error.message}")
                    }
                    )
                }
            } else {
                // Try offline translation
                Log.d(TAG, "Attempting offline translation")
                // Detect source language from text
                val sourceLanguageCode = translationService.detectLanguagePublic(translationInput)
                val sourceLanguage = Language.fromCode(sourceLanguageCode)

                // Check if models are downloaded
                val modelsDownloaded = mlKitService.areModelsDownloaded(
                    sourceLanguage.mlKitCode,
                    targetLanguage.mlKitCode
                )

                if (!modelsDownloaded) {
                    Log.w(TAG, "Offline models not downloaded: ${sourceLanguage.code} -> ${targetLanguage.code}")
                    return@withContext Result.failure(
                        ModelNotDownloadedException(
                            "Offline translation models not downloaded. Please download models in Settings.",
                            sourceLanguage,
                            targetLanguage
                        )
                    )
                }

                mlKitService.translateText(translationInput, sourceLanguage.mlKitCode, targetLanguage.mlKitCode)
                    .map { translatedText -> restoreBulletMarkers(translatedText) }
            }
        } catch (e: ModelNotDownloadedException) {
            Log.e(TAG, "Model not downloaded: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("TranslationManager", "Exception in translateText", e)
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

    /**
     * Download offline model for a language
     */
    suspend fun downloadOfflineModel(language: Language, onProgress: (Int) -> Unit = {}): Result<Unit> {
        return mlKitService.downloadModel(language.mlKitCode, onProgress)
    }

    /**
     * Delete offline model for a language
     */
    suspend fun deleteOfflineModel(language: Language): Result<Unit> {
        return mlKitService.deleteModel(language.mlKitCode)
    }

    /**
     * Get offline model status for all languages
     */
    suspend fun getOfflineModelStatus(): Map<Language, Boolean> {
        val status = mutableMapOf<Language, Boolean>()
        for (language in Language.values()) {
            val isDownloaded = mlKitService.areModelsDownloaded(language.mlKitCode, "en")
            status[language] = isDownloaded
        }
        return status
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        mlKitService.close()
    }
}
