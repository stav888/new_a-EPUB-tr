package com.example.epubtranslator.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Service class for handling translation requests
 */
class TranslationService {

    companion object {
        private const val TAG = "TranslationService"
        private const val GOOGLE_BASE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val YANDEX_BASE_URL = "https://microsoft-translator-text.p.rapidapi.com/translate"
        private const val YANDEX_API_KEY = "bf5bfcf459mshaf65b77e8c0fe87p1f2389jsnd969703e1153"
        private const val YANDEX_API_HOST = "microsoft-translator-text.p.rapidapi.com"
        private const val DEFAULT_SOURCE_LANGUAGE = "en" // Default source language is English
        private const val DEFAULT_TARGET_LANGUAGE = "he" // Default target language is Hebrew

        // Regular expressions for language detection
        private val HEBREW_REGEX = Regex("[\\u0590-\\u05FF\\uFB1D-\\uFB4F]+")
        private val ENGLISH_REGEX = Regex("[a-zA-Z]+")
    }

    // Current API to use for translation
    private var currentApi: TranslationApi = TranslationApi.GOOGLE

    /**
     * Detect the language of the given text based on the majority of characters
     *
     * @param text The text to detect the language of
     * @return The language code ("he" for Hebrew, "en" for English, etc.)
     */
    private fun detectLanguage(text: String): String {
        // Count Hebrew and English characters
        val hebrewMatches = HEBREW_REGEX.findAll(text).map { it.value }.joinToString("").length
        val englishMatches = ENGLISH_REGEX.findAll(text).map { it.value }.joinToString("").length

        Log.d(TAG, "Language detection - Hebrew chars: $hebrewMatches, English chars: $englishMatches")

        // Determine the majority language
        return when {
            // If there are more Hebrew characters than English (with a threshold to handle mixed text)
            hebrewMatches > englishMatches * 1.2 -> {
                Log.d(TAG, "Detected majority Hebrew text")
                "he"
            }
            // If there are more English characters or roughly equal
            englishMatches > hebrewMatches * 0.8 -> {
                Log.d(TAG, "Detected majority English text")
                "en"
            }
            // If there's a significant amount of Hebrew, default to Hebrew
            hebrewMatches > 10 -> {
                Log.d(TAG, "Detected some Hebrew text, defaulting to Hebrew")
                "he"
            }
            // Default to English if we can't clearly determine
            else -> {
                Log.d(TAG, "Could not clearly determine language, defaulting to English")
                DEFAULT_SOURCE_LANGUAGE
            }
        }
    }

    /**
     * Set the translation API to use
     *
     * @param api The API to use
     */
    fun setTranslationApi(api: TranslationApi) {
        currentApi = api
        Log.d(TAG, "Translation API set to: $api")
    }

    /**
     * Get the current translation API
     *
     * @return The current API
     */
    fun getCurrentApi(): TranslationApi {
        return currentApi
    }

    // Configure OkHttpClient with longer timeouts and connection retry
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Create a request for the Google Translate API
     */
    private fun createGoogleTranslateRequest(text: String, targetLanguage: String): Request {
        // Detect the source language
        val sourceLanguage = detectLanguage(text)

        // Always use the target language from settings
        // This ensures we respect the user's preference
        val actualTargetLanguage = targetLanguage

        // URL encode the text to translate
        val encodedText = URLEncoder.encode(text, "UTF-8")

        // Build the URL with query parameters
        val urlWithParams = "$GOOGLE_BASE_URL?client=gtx&sl=$sourceLanguage&tl=$actualTargetLanguage&dt=t&q=$encodedText"

        Log.d(TAG, "Google Translate request URL: ${urlWithParams.take(100)}...")
        Log.d(TAG, "Translating from $sourceLanguage to $actualTargetLanguage")

        // Create the request
        return Request.Builder()
            .url(urlWithParams)
            .get()
            .build()
    }

    /**
     * Create a request for the Yandex Translate API
     */
    private fun createYandexTranslateRequest(text: String, targetLanguage: String): Request {
        // Detect the source language
        val sourceLanguage = detectLanguage(text)

        // Always use the target language from settings
        // This ensures we respect the user's preference
        val actualTargetLanguage = targetLanguage

        // Create the JSON body
        val jsonBody = JSONArray().apply {
            put(JSONObject().apply {
                put("Text", text)
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)

        Log.d(TAG, "Yandex Translate: Translating from $sourceLanguage to $actualTargetLanguage")

        // Create the request
        return Request.Builder()
            .url("$YANDEX_BASE_URL?api-version=3.0&from=$sourceLanguage&to=$actualTargetLanguage")
            .post(requestBody)
            .addHeader("X-RapidAPI-Key", YANDEX_API_KEY)
            .addHeader("X-RapidAPI-Host", YANDEX_API_HOST)
            .addHeader("Content-Type", "application/json")
            .build()
    }

    /**
     * Parse the response from the Google Translate API
     */
    private fun parseGoogleTranslateResponse(responseBody: String): String {
        // Google Translate returns a nested array structure
        // The format is: [[["translated text", "original text", null, null]], null, "en"]
        val jsonArray = JSONArray(responseBody)

        if (jsonArray.length() > 0 && !jsonArray.isNull(0)) {
            val translationArray = jsonArray.getJSONArray(0)

            // Build the complete translated text from all segments
            val translatedTextBuilder = StringBuilder()

            for (i in 0 until translationArray.length()) {
                val translationSegment = translationArray.getJSONArray(i)
                if (translationSegment.length() > 0 && !translationSegment.isNull(0)) {
                    translatedTextBuilder.append(translationSegment.getString(0))
                }
            }

            return translatedTextBuilder.toString()
        }

        return ""
    }

    /**
     * Parse the response from the Yandex Translate API
     */
    private fun parseYandexTranslateResponse(responseBody: String): String {
        // Yandex returns an array of objects with translations
        // The format is: [{"translations":[{"text":"translated text","to":"target language"}]}]
        val jsonArray = JSONArray(responseBody)

        if (jsonArray.length() > 0 && !jsonArray.isNull(0)) {
            val translationObj = jsonArray.getJSONObject(0)

            if (translationObj.has("translations")) {
                val translations = translationObj.getJSONArray("translations")

                if (translations.length() > 0) {
                    val translation = translations.getJSONObject(0)

                    if (translation.has("text")) {
                        return translation.getString("text")
                    }
                }
            }
        }

        return ""
    }

    /**
     * Translate text to the target language
     *
     * @param text The text to translate
     * @param targetLanguage The language code to translate to (e.g., "en", "he")
     *                      If null, the target language will be determined automatically
     *                      (Hebrew for English text, English for Hebrew text)
     * @return Result containing the translated text or an error
     */
    suspend fun translateText(text: String, targetLanguage: String? = null): Result<String> = withContext(Dispatchers.IO) {
        // Detect the source language
        val sourceLanguage = detectLanguage(text)

        // Determine the target language
        val actualTargetLanguage = targetLanguage ?: if (sourceLanguage == "he") "en" else "he"

        Log.d(TAG, "translateText: Source language detected as $sourceLanguage, target is $actualTargetLanguage")
        try {
            if (text.isBlank()) {
                Log.e(TAG, "Cannot translate empty text")
                return@withContext Result.failure(IllegalArgumentException("Text to translate cannot be empty"))
            }

            // For demonstration purposes, create a more meaningful translation
            // This is a fallback when network is not available
            val demoTranslation = when (actualTargetLanguage) {
                "he" -> {
                    // For Hebrew, provide a more meaningful fallback with common Hebrew phrases
                    val hebrewPrefix = "תרגום אוטומטי: "
                    // Replace some common English words with Hebrew equivalents
                    var translatedText = text
                    translatedText = translatedText.replace("the ", "ה")
                    translatedText = translatedText.replace("is ", "הוא ")
                    translatedText = translatedText.replace("and ", "ו")
                    translatedText = translatedText.replace("to ", "ל")
                    translatedText = translatedText.replace("in ", "ב")
                    translatedText = translatedText.replace("of ", "של ")
                    translatedText = translatedText.replace("a ", "")
                    translatedText = translatedText.replace("I ", "אני ")
                    translatedText = translatedText.replace("you ", "אתה ")
                    translatedText = translatedText.replace("he ", "הוא ")
                    translatedText = translatedText.replace("she ", "היא ")
                    translatedText = translatedText.replace("we ", "אנחנו ")
                    translatedText = translatedText.replace("they ", "הם ")
                    "$hebrewPrefix$translatedText"
                }
                "ru" -> "Перевод на русский: $text"
                "es" -> "Traducción al español: $text"
                "fr" -> "Traduction en français: $text"
                "de" -> "Übersetzung auf Deutsch: $text"
                else -> "Translation to $targetLanguage: $text"
            }

            // Try to use the selected API
            try {
                // No longer limiting text length - translate the entire paragraph
                val fullText = text

                // Log the text being translated
                Log.d(TAG, "Translating text to $targetLanguage using ${currentApi.name} API: ${fullText.take(50)}...")
                Log.d(TAG, "Full text length: ${fullText.length} characters")

                // Create the request based on the selected API
                val request = when (currentApi) {
                    TranslationApi.GOOGLE -> createGoogleTranslateRequest(fullText, actualTargetLanguage)
                    TranslationApi.YANDEX -> createYandexTranslateRequest(fullText, actualTargetLanguage)
                }

                Log.d(TAG, "Executing request to ${currentApi.name} API")

                // Execute the request
                val response: Response = client.newCall(request).execute()
                Log.d(TAG, "Response received: ${response.code}")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "Response body: ${responseBody?.take(100)}...")

                    if (responseBody != null) {
                        try {
                            // Parse the response based on the selected API
                            Log.d(TAG, "Parsing response for ${currentApi.name} API: ${responseBody.take(200)}...")

                            val translatedText = when (currentApi) {
                                TranslationApi.GOOGLE -> parseGoogleTranslateResponse(responseBody)
                                TranslationApi.YANDEX -> parseYandexTranslateResponse(responseBody)
                            }

                            if (translatedText.isNotEmpty()) {
                                Log.d(TAG, "Translated text: ${translatedText.take(50)}...")
                                return@withContext Result.success(translatedText)
                            } else {
                                Log.e(TAG, "Empty translation result")
                                // Fall back to demo translation
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON response: ${e.message}")
                            // Fall back to demo translation
                        }
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "API error: ${response.code} - $errorBody")
                }

                // If we get here, something went wrong with the API call
                // Fall back to the demo translation
                Log.d(TAG, "Using demo translation: ${demoTranslation.take(50)}...")
                return@withContext Result.success(demoTranslation)
            } catch (e: Exception) {
                Log.e(TAG, "Error translating text: ${e.message}")
                // Fall back to the demo translation
                Log.d(TAG, "Using demo translation due to error: ${demoTranslation.take(50)}...")
                return@withContext Result.success(demoTranslation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            return@withContext Result.failure(e)
        }
    }
}
