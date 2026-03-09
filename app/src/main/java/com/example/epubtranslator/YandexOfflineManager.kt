package com.example.epubtranslator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.epubtranslator.translation.Language
import java.io.File
import java.util.concurrent.Executors

/**
 * Data class for offline language management
 */
data class OfflineLanguage(
    val code: String,
    val name: String,
    var isDownloaded: Boolean = false,
    var isDownloading: Boolean = false,
    var downloadProgress: Int = 0
)

class YandexOfflineManager(private val context: Context) {
    private val TAG = "YandexOfflineManager"
    private val executor = Executors.newSingleThreadExecutor()
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "yandex_offline_prefs", Context.MODE_PRIVATE
    )

    // Directory to store offline language files
    private val offlineDir: File
        get() {
            val dir = File(context.filesDir, "yandex_offline")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    // Get all supported languages
    fun getSupportedLanguages(): List<OfflineLanguage> {
        return listOf(
            OfflineLanguage("en", "English"),
            OfflineLanguage("ru", "Russian"),
            OfflineLanguage("fr", "French"),
            OfflineLanguage("de", "German"),
            OfflineLanguage("es", "Spanish"),
            OfflineLanguage("it", "Italian"),
            OfflineLanguage("pt", "Portuguese"),
            OfflineLanguage("zh", "Chinese"),
            OfflineLanguage("ja", "Japanese"),
            OfflineLanguage("ko", "Korean"),
            OfflineLanguage("ar", "Arabic"),
            OfflineLanguage("he", "Hebrew"),
            OfflineLanguage("hi", "Hindi"),
            OfflineLanguage("tr", "Turkish")
        ).map { language ->
            // Check if this language is already downloaded
            language.isDownloaded = isLanguageDownloaded(language.code)
            language
        }
    }

    // Check if a language is downloaded
    fun isLanguageDownloaded(languageCode: String): Boolean {
        return sharedPreferences.getBoolean("downloaded_$languageCode", false) &&
                File(offlineDir, "$languageCode.dat").exists()
    }

    // Download a language
    fun downloadLanguage(
        language: OfflineLanguage,
        progressCallback: (Int) -> Unit,
        completionCallback: (Boolean) -> Unit
    ) {
        executor.execute {
            try {
                // Simulate download with progress updates
                language.isDownloading = true
                for (progress in 0..100 step 10) {
                    Thread.sleep(500) // Simulate network delay
                    language.downloadProgress = progress
                    progressCallback(progress)
                }

                // Create a dummy file to represent the downloaded language data
                val file = File(offlineDir, "${language.code}.dat")
                file.createNewFile()
                file.writeText("This is a simulated offline language file for ${language.name}")

                // Mark as downloaded in preferences
                sharedPreferences.edit().putBoolean("downloaded_${language.code}", true).apply()

                language.isDownloaded = true
                language.isDownloading = false
                completionCallback(true)
                Log.d(TAG, "Downloaded language: ${language.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading language: ${language.name}", e)
                language.isDownloading = false
                completionCallback(false)
            }
        }
    }

    // Delete a downloaded language
    fun deleteLanguage(language: OfflineLanguage, completionCallback: (Boolean) -> Unit) {
        executor.execute {
            try {
                val file = File(offlineDir, "${language.code}.dat")
                val deleted = file.delete()

                if (deleted) {
                    // Update preferences
                    sharedPreferences.edit().putBoolean("downloaded_${language.code}", false).apply()
                    language.isDownloaded = false
                    completionCallback(true)
                    Log.d(TAG, "Deleted language: ${language.name}")
                } else {
                    completionCallback(false)
                    Log.e(TAG, "Failed to delete language: ${language.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting language: ${language.name}", e)
                completionCallback(false)
            }
        }
    }
}
