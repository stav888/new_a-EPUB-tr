package com.example.epubtranslator.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Manager class for handling book positions (last page viewed)
 */
class BookPositionManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "book_position_prefs"
        private const val TRANSLATION_PREFS_NAME = "book_translation_prefs"
        private const val KEY_PREFIX_POSITION = "position_"
        private const val KEY_PREFIX_SCROLL = "scroll_"
        private const val KEY_PREFIX_TRANSLATION = "translation_"
        private const val TAG = "BookPositionManager"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val translationPrefs: SharedPreferences = context.getSharedPreferences(TRANSLATION_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Save the current page position for a book
     */
    fun savePosition(filePath: String, position: Int) {
        val key = getKeyForFile(filePath)
        prefs.edit().putInt(key, position).apply()
    }

    /**
     * Get the last saved position for a book
     * Returns 0 if no position was saved
     */
    fun getPosition(filePath: String): Int {
        val key = getKeyForFile(filePath)
        return prefs.getInt(key, 0)
    }

    /**
     * Save the scroll position for a book
     */
    fun saveScrollPosition(filePath: String, scrollY: Int) {
        val key = getScrollKeyForFile(filePath)
        prefs.edit().putInt(key, scrollY).apply()
    }

    /**
     * Get the last saved scroll position for a book
     * Returns 0 if no position was saved
     */
    fun getScrollPosition(filePath: String): Int {
        val key = getScrollKeyForFile(filePath)
        return prefs.getInt(key, 0)
    }

    /**
     * Clear the saved position for a book
     */
    fun clearPosition(filePath: String) {
        val positionKey = getKeyForFile(filePath)
        val scrollKey = getScrollKeyForFile(filePath)
        prefs.edit()
            .remove(positionKey)
            .remove(scrollKey)
            .apply()
    }

    /**
     * Clear all saved positions (for testing/debugging)
     */
    fun clearAllPositions() {
        prefs.edit().clear().apply()
    }

    /**
     * Save translations for a specific page of a book with visibility state and target languages
     */
    fun savePageTranslations(filePath: String, pageNumber: Int, translations: Map<String, String>, visibleTranslations: Set<String> = emptySet(), targetLanguages: Map<String, String> = emptyMap()) {
        val key = getTranslationKeyForPage(filePath, pageNumber)

        try {
            // Create JSON object with translations, visibility state, and target languages
            val jsonObject = JSONObject()
            val translationsObject = JSONObject()
            val visibilityArray = org.json.JSONArray()
            val languagesObject = JSONObject()

            // Store translations
            for ((originalText, translatedText) in translations) {
                translationsObject.put(originalText, translatedText)
            }

            // Store which translations are currently visible
            for (visibleText in visibleTranslations) {
                visibilityArray.put(visibleText)
            }

            // Store target languages for each translation
            for ((originalText, targetLanguage) in targetLanguages) {
                languagesObject.put(originalText, targetLanguage)
            }

            jsonObject.put("translations", translationsObject)
            jsonObject.put("visible", visibilityArray)
            jsonObject.put("languages", languagesObject)

            val jsonString = jsonObject.toString()
            translationPrefs.edit().putString(key, jsonString).apply()

            Log.d(TAG, "Saved ${translations.size} translations (${visibleTranslations.size} visible) with target languages for page $pageNumber of ${File(filePath).name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving translations for page $pageNumber", e)
        }
    }

    /**
     * Get saved translations for a specific page of a book
     * Returns a triple of (translations map, visible translations set, target languages map)
     */
    fun getPageTranslations(filePath: String, pageNumber: Int): Triple<MutableMap<String, String>, MutableSet<String>, MutableMap<String, String>> {
        val key = getTranslationKeyForPage(filePath, pageNumber)
        val translations = mutableMapOf<String, String>()
        val visibleTranslations = mutableSetOf<String>()
        val targetLanguages = mutableMapOf<String, String>()

        try {
            val jsonString = translationPrefs.getString(key, null)
            if (!jsonString.isNullOrEmpty()) {
                val jsonObject = JSONObject(jsonString)

                // Handle different formats: old (direct translations), new (with visibility), and newest (with languages)
                if (jsonObject.has("translations") && jsonObject.has("visible")) {
                    // New format with visibility state and possibly target languages
                    val translationsObject = jsonObject.getJSONObject("translations")
                    val visibilityArray = jsonObject.getJSONArray("visible")

                    // Load translations
                    val keys = translationsObject.keys()
                    while (keys.hasNext()) {
                        val originalText = keys.next()
                        val translatedText = translationsObject.getString(originalText)
                        translations[originalText] = translatedText
                    }

                    // Load visibility state
                    for (i in 0 until visibilityArray.length()) {
                        visibleTranslations.add(visibilityArray.getString(i))
                    }

                    // Load target languages if available
                    if (jsonObject.has("languages")) {
                        val languagesObject = jsonObject.getJSONObject("languages")
                        val languageKeys = languagesObject.keys()
                        while (languageKeys.hasNext()) {
                            val originalText = languageKeys.next()
                            val targetLanguage = languagesObject.getString(originalText)
                            targetLanguages[originalText] = targetLanguage
                        }
                    }

                    Log.d(TAG, "Loaded ${translations.size} translations (${visibleTranslations.size} visible, ${targetLanguages.size} with languages) for page $pageNumber of ${File(filePath).name}")
                } else {
                    // Old format - treat all translations as visible for backward compatibility
                    val keys = jsonObject.keys()
                    while (keys.hasNext()) {
                        val originalText = keys.next()
                        val translatedText = jsonObject.getString(originalText)
                        translations[originalText] = translatedText
                        visibleTranslations.add(originalText) // Mark as visible for backward compatibility
                        // No target language info available for old format
                    }

                    Log.d(TAG, "Loaded ${translations.size} translations (old format, all visible, no language info) for page $pageNumber of ${File(filePath).name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading translations for page $pageNumber", e)
        }

        return Triple(translations, visibleTranslations, targetLanguages)
    }

    /**
     * Save all translations for a book (all pages) with visibility state and target languages
     */
    fun saveAllBookTranslations(filePath: String, allPageTranslations: Map<Int, Map<String, String>>, allVisibleTranslations: Map<Int, Set<String>> = emptyMap(), allTargetLanguages: Map<Int, Map<String, String>> = emptyMap()) {
        try {
            for ((pageNumber, translations) in allPageTranslations) {
                if (translations.isNotEmpty()) {
                    val visibleTranslations = allVisibleTranslations[pageNumber] ?: emptySet()
                    val targetLanguages = allTargetLanguages[pageNumber] ?: emptyMap()
                    savePageTranslations(filePath, pageNumber, translations, visibleTranslations, targetLanguages)
                }
            }
            Log.d(TAG, "Saved translations for ${allPageTranslations.size} pages with target languages of ${File(filePath).name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving all book translations", e)
        }
    }

    /**
     * Load all translations for a book (all pages) with visibility state and target languages
     * Returns a triple of (all translations, all visible translations, all target languages)
     */
    fun loadAllBookTranslations(filePath: String, totalPages: Int): Triple<MutableMap<Int, MutableMap<String, String>>, MutableMap<Int, MutableSet<String>>, MutableMap<Int, MutableMap<String, String>>> {
        val allTranslations = mutableMapOf<Int, MutableMap<String, String>>()
        val allVisibleTranslations = mutableMapOf<Int, MutableSet<String>>()
        val allTargetLanguages = mutableMapOf<Int, MutableMap<String, String>>()

        try {
            for (pageNumber in 0 until totalPages) {
                val (pageTranslations, visibleTranslations, targetLanguages) = getPageTranslations(filePath, pageNumber)
                if (pageTranslations.isNotEmpty()) {
                    allTranslations[pageNumber] = pageTranslations
                    allVisibleTranslations[pageNumber] = visibleTranslations
                    allTargetLanguages[pageNumber] = targetLanguages
                }
            }

            Log.d(TAG, "Loaded translations for ${allTranslations.size} pages with target languages of ${File(filePath).name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all book translations", e)
        }

        return Triple(allTranslations, allVisibleTranslations, allTargetLanguages)
    }

    /**
     * Clear translations for a specific page
     */
    fun clearPageTranslations(filePath: String, pageNumber: Int) {
        val key = getTranslationKeyForPage(filePath, pageNumber)
        translationPrefs.edit().remove(key).apply()
        Log.d(TAG, "Cleared translations for page $pageNumber of ${File(filePath).name}")
    }

    /**
     * Clear all translations for a book
     */
    fun clearBookTranslations(filePath: String) {
        val fileName = File(filePath).name
        val pathHash = filePath.hashCode()

        // Remove all translation keys for this book
        val editor = translationPrefs.edit()
        val allKeys = translationPrefs.all.keys

        for (key in allKeys) {
            if (key.contains("${fileName}_$pathHash")) {
                editor.remove(key)
            }
        }

        editor.apply()
        Log.d(TAG, "Cleared all translations for ${File(filePath).name}")
    }

    /**
     * Generate a unique key for the file path (page position)
     */
    private fun getKeyForFile(filePath: String): String {
        // Use the file name as part of the key to make it more readable
        val fileName = File(filePath).name

        // Create a hash of the full path to ensure uniqueness
        val pathHash = filePath.hashCode()

        return "${KEY_PREFIX_POSITION}${fileName}_$pathHash"
    }

    /**
     * Generate a unique key for the file path (scroll position)
     */
    private fun getScrollKeyForFile(filePath: String): String {
        // Use the file name as part of the key to make it more readable
        val fileName = File(filePath).name

        // Create a hash of the full path to ensure uniqueness
        val pathHash = filePath.hashCode()

        return "${KEY_PREFIX_SCROLL}${fileName}_$pathHash"
    }

    /**
     * Generate a unique key for translations for a specific page
     */
    private fun getTranslationKeyForPage(filePath: String, pageNumber: Int): String {
        // Use the file name as part of the key to make it more readable
        val fileName = File(filePath).name

        // Create a hash of the full path to ensure uniqueness
        val pathHash = filePath.hashCode()

        return "${KEY_PREFIX_TRANSLATION}${fileName}_${pathHash}_page_$pageNumber"
    }
}
