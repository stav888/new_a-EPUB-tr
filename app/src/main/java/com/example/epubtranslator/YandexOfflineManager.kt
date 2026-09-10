package com.example.epubtranslator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.epubtranslator.translation.Language
import com.example.epubtranslator.translation.MlKitTranslationService
import com.example.epubtranslator.translation.OfflineLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manager for offline translation using ML Kit
 * Delegates to MlKitTranslationService for actual model management
 */
class YandexOfflineManager(private val context: Context) {
    private val TAG = "YandexOfflineManager"
    private val mlKitService = MlKitTranslationService()
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "mlkit_offline_prefs", Context.MODE_PRIVATE
    )

    // Get all supported languages
    fun getSupportedLanguages(): List<OfflineLanguage> {
        return Language.values().map { language ->
            OfflineLanguage(
                code = language.code,
                name = language.displayName,
                mlKitCode = language.mlKitCode,
                isDownloaded = isLanguageDownloaded(language.mlKitCode)
            )
        }
    }

    // Check if a language is downloaded
    fun isLanguageDownloaded(languageCode: String): Boolean {
        return sharedPreferences.getBoolean("downloaded_$languageCode", false)
    }

    // Refresh download status for all languages
    suspend fun refreshDownloadStatus(languages: List<OfflineLanguage>) {
        for (language in languages) {
            val isDownloaded = mlKitService.areModelsDownloaded(language.mlKitCode, "en")
            language.isDownloaded = isDownloaded
            sharedPreferences.edit().putBoolean("downloaded_${language.mlKitCode}", isDownloaded).apply()
        }
    }

    // Download a language
    fun downloadLanguage(
        language: OfflineLanguage,
        progressCallback: (Int) -> Unit,
        completionCallback: (Boolean) -> Unit
    ) {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    language.isDownloading = true
                }

                val result = mlKitService.downloadModel(language.mlKitCode) { progress ->
                    language.downloadProgress = progress
                    progressCallback(progress)
                }

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = {
                            language.isDownloaded = true
                            language.isDownloading = false
                            sharedPreferences.edit().putBoolean("downloaded_${language.mlKitCode}", true).apply()
                            completionCallback(true)
                            Log.d(TAG, "Downloaded language: ${language.name}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Error downloading language: ${language.name}", error)
                            language.isDownloading = false
                            completionCallback(false)
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Exception downloading language: ${language.name}", e)
                    language.isDownloading = false
                    completionCallback(false)
                }
            }
        }
    }

    // Delete a downloaded language
    fun deleteLanguage(language: OfflineLanguage, completionCallback: (Boolean) -> Unit) {
        coroutineScope.launch {
            try {
                val result = mlKitService.deleteModel(language.mlKitCode)

                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = {
                            language.isDownloaded = false
                            sharedPreferences.edit().putBoolean("downloaded_${language.mlKitCode}", false).apply()
                            completionCallback(true)
                            Log.d(TAG, "Deleted language: ${language.name}")
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Error deleting language: ${language.name}", error)
                            completionCallback(false)
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Exception deleting language: ${language.name}", e)
                    completionCallback(false)
                }
            }
        }
    }

    // Cleanup resources
    fun onDestroy() {
        coroutineScope.cancel()
        mlKitService.close()
    }
}
