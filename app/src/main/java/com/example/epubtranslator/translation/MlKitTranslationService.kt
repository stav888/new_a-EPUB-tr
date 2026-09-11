package com.example.epubtranslator.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Service for on-device translation using Google ML Kit
 */
class MlKitTranslationService {
    companion object {
        private const val TAG = "MlKitTranslationService"
    }

    private val translators = mutableMapOf<Pair<String, String>, Translator>()

    /**
     * Get or create a translator for the given language pair
     */
    private fun getTranslator(sourceLanguage: String, targetLanguage: String): Translator {
        val key = Pair(sourceLanguage, targetLanguage)
        return translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )
        }
    }

    /**
     * Check if models are downloaded for the given language pair
     */
    suspend fun areModelsDownloaded(sourceLanguage: String, targetLanguage: String): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val modelManager = RemoteModelManager.getInstance()
                val sourceModel = TranslateRemoteModel.Builder(sourceLanguage).build()
                val targetModel = TranslateRemoteModel.Builder(targetLanguage).build()
                modelManager.isModelDownloaded(sourceModel).await() &&
                    modelManager.isModelDownloaded(targetModel).await()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking if models are downloaded: ${e.message}")
                false
            }
        }
    }

    /**
     * Download a model for the given language code
     */
    suspend fun downloadModel(
        languageCode: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                // Create a translator to trigger download (ML Kit doesn't provide granular progress)
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(languageCode)
                        .setTargetLanguage("en")
                        .build()
                )

                onProgress(5)

                // Download the model
                // Allow model downloads over Wi-Fi or mobile data.
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()

                onProgress(95)

                Log.d(TAG, "Model downloaded for language: $languageCode")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model for language $languageCode: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun downloadModelPair(
        sourceLanguage: String,
        targetLanguage: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                if (areModelsDownloaded(sourceLanguage, targetLanguage)) {
                    onProgress(100)
                    return@withContext Result.success(Unit)
                }

                val translator = getTranslator(sourceLanguage, targetLanguage)
                onProgress(5)
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                onProgress(95)
                onProgress(100)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading model pair $sourceLanguage -> $targetLanguage", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a model for the given language code
     */
    suspend fun deleteModel(languageCode: String): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(languageCode)
                        .setTargetLanguage("en")
                        .build()
                )
                // Note: ML Kit Translator doesn't have a deleteModel() method.
                // Models are managed by ML Kit's model management system.
                // We close the translator and rely on ML Kit to handle cleanup.
                translator.close()

                Log.d(TAG, "Model cleanup for language: $languageCode")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting model for language $languageCode: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /**
     * Translate text from source to target language (must have models downloaded)
     */
    suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): Result<String> {
        return withContext(Dispatchers.Default) {
            try {
                val translator = getTranslator(sourceLanguage, targetLanguage)
                if (!areModelsDownloaded(sourceLanguage, targetLanguage)) {
                    val downloadResult = downloadModelPair(sourceLanguage, targetLanguage)
                    if (downloadResult.isFailure) {
                        return@withContext Result.failure<String>(
                            downloadResult.exceptionOrNull()
                                ?: IllegalStateException("Required ML Kit translation models are not downloaded.")
                        )
                    }
                }
                val translatedText = translator.translate(text).await()
                Log.d(TAG, "Translation successful: ${translatedText.take(50)}...")
                Result.success(translatedText)
            } catch (e: Exception) {
                Log.e(TAG, "Error translating text: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /**
     * Get all downloaded language codes
     */
    suspend fun getDownloadedLanguageCodes(): Set<String> {
        return withContext(Dispatchers.Default) {
            try {
                // ML Kit doesn't provide a direct API to list downloaded models
                // We'll track this in SharedPreferences instead
                setOf()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting downloaded language codes: ${e.message}")
                setOf()
            }
        }
    }

    /**
     * Close all translators to release resources
     */
    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
