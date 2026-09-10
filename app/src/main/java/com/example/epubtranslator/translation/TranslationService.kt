package com.example.epubtranslator.translation

import android.util.Log
import com.example.epubtranslator.BuildConfig
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
        private const val MICROSOFT_TRANSLATOR_BASE_URL = "https://microsoft-translator-text.p.rapidapi.com/translate"
        private const val DEFAULT_SOURCE_LANGUAGE = "en" // Default source language is English
        private const val DEFAULT_TARGET_LANGUAGE = "he" // Default target language is Hebrew

        // Regular expressions for language detection
        private val HEBREW_REGEX = Regex("[\\u0590-\\u05FF\\uFB1D-\\uFB4F]+")
        private val ENGLISH_REGEX = Regex("[a-zA-Z]+")
    }

    // API credentials from BuildConfig (set in build.gradle)
    private val rapidApiKey: String = BuildConfig.RAPIDAPI_KEY
    private val rapidApiHost: String = BuildConfig.RAPIDAPI_HOST

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

        Log.d(TAG, "🔍 Language detection for text: '${text.take(50)}...'")
        Log.d(TAG, "🔍 Hebrew chars: $hebrewMatches, English chars: $englishMatches")

        // Determine the majority language with improved logic
        val detectedLanguage = when {
            // If there are Hebrew characters and they dominate
            hebrewMatches > 0 && hebrewMatches >= englishMatches -> {
                Log.d(TAG, "✅ Detected Hebrew text (Hebrew: $hebrewMatches >= English: $englishMatches)")
                "he"
            }
            // If there are English characters and they dominate
            englishMatches > 0 && englishMatches > hebrewMatches -> {
                Log.d(TAG, "✅ Detected English text (English: $englishMatches > Hebrew: $hebrewMatches)")
                "en"
            }
            // If both are zero or equal, check for common patterns
            else -> {
                // Check for common English words
                val commonEnglishWords = listOf("the", "and", "is", "in", "to", "it", "that", "was", "for", "with", "as", "by", "on", "at", "be", "or", "an", "are", "from", "any", "have", "this", "but", "not", "what", "all", "were", "they", "we", "been", "has", "had", "which", "she", "do", "if", "will", "up", "other", "about", "out", "many", "then", "them", "these", "so", "some", "her", "would", "make", "like", "into", "him", "time", "two", "more", "go", "no", "way", "could", "my", "than", "first", "water", "long", "little", "very", "after", "words", "without", "just", "where", "most", "know", "get", "through", "back", "much", "before", "good", "new", "write", "our", "used", "me", "man", "too", "old", "see", "now", "over", "did", "down", "only", "way", "find", "use", "may", "say", "each", "which", "their", "said", "work", "life", "right", "move", "try", "cause", "again", "off", "went", "old", "number", "great", "tell", "men", "say", "small", "every", "found", "still", "between", "name", "should", "home", "big", "give", "air", "line", "set", "own", "under", "read", "last", "never", "us", "left", "end", "why", "called", "didn't", "look", "asked", "later", "knew", "point", "next", "came", "take", "important", "children", "took", "got", "hear", "example", "begin", "life", "always", "those", "both", "paper", "together", "got", "group", "often", "run", "important", "until", "children", "side", "feet", "car", "mile", "night", "walk", "white", "sea", "began", "grow", "took", "river", "four", "carry", "state", "once", "book", "hear", "stop", "without", "second", "later", "miss", "idea", "enough", "eat", "face", "watch", "far", "indian", "really", "almost", "let", "above", "girl", "sometimes", "mountain", "cut", "young", "talk", "soon", "list", "song", "being", "leave", "family", "it's")
                val textLower = text.lowercase()
                val englishWordCount = commonEnglishWords.count { textLower.contains(it) }

                if (englishWordCount >= 3) {
                    Log.d(TAG, "✅ Detected English by common words (found $englishWordCount common words)")
                    "en"
                } else {
                    Log.d(TAG, "⚠️ Could not clearly determine language, defaulting to English (Hebrew: $hebrewMatches, English: $englishMatches, Common words: $englishWordCount)")
                    DEFAULT_SOURCE_LANGUAGE
                }
            }
        }

        Log.d(TAG, "🎯 Final detected language: $detectedLanguage")
        return detectedLanguage
    }

    // Public wrapper to expose language detection
    fun detectLanguagePublic(text: String): String = detectLanguage(text)

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

    // Configure OkHttpClient with fast timeouts for quick response
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS) // Quick fallback to demo translation
        .retryOnConnectionFailure(false) // Don't retry to keep it fast
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
     * Create a request for the Microsoft Translator API (via RapidAPI)
     */
    private fun createMicrosoftTranslatorRequest(text: String, targetLanguage: String): Request {
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

        Log.d(TAG, "Microsoft Translator: Translating from $sourceLanguage to $actualTargetLanguage")

        // Create the request
        return Request.Builder()
            .url("$MICROSOFT_TRANSLATOR_BASE_URL?api-version=3.0&from=$sourceLanguage&to=$actualTargetLanguage")
            .post(requestBody)
            .addHeader("X-RapidAPI-Key", rapidApiKey)
            .addHeader("X-RapidAPI-Host", rapidApiHost)
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
        Log.d(TAG, "🚀 Starting translation process...")
        Log.d(TAG, "🚀 Input text: '${text.take(100)}...' (length: ${text.length})")
        Log.d(TAG, "🚀 Requested target language: $targetLanguage")

        // Detect the source language
        val sourceLanguage = detectLanguage(text)

        // Determine the target language (respect user setting via parameter)
        var effectiveTarget = targetLanguage ?: if (sourceLanguage == "he") "en" else "he"

        // If source and target are the same, only pick a fallback when target not explicitly provided
        if (targetLanguage == null && sourceLanguage == effectiveTarget) {
            val fallback = if (effectiveTarget == "he") "en" else "he"
            Log.w(TAG, "⚠️ Source and target are the same ($sourceLanguage) with auto-target. Using fallback: $fallback")
            effectiveTarget = fallback
        }

        Log.d(TAG, "🎯 Final translation plan: $sourceLanguage → $effectiveTarget")

        try {
            if (text.isBlank()) {
                Log.e(TAG, "❌ Cannot translate empty text")
                return@withContext Result.failure(IllegalArgumentException("Text to translate cannot be empty"))
            }



            // Try to use the selected API
            try {
                // No longer limiting text length - translate the entire paragraph
                val fullText = text

                // Log the text being translated
                Log.d(TAG, "Translating text to $effectiveTarget using ${currentApi.name} API: ${fullText.take(50)}...")
                Log.d(TAG, "Full text length: ${fullText.length} characters")

                // Create the request based on the selected API
                val request = when (currentApi) {
                    TranslationApi.GOOGLE -> createGoogleTranslateRequest(fullText, effectiveTarget)
                    TranslationApi.YANDEX -> createMicrosoftTranslatorRequest(fullText, effectiveTarget)
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

                // Do not report a fake translation as success. The reader will clear
                // the loading state and refund the reserved credit on this failure.
                return@withContext Result.failure(
                    IllegalStateException("Translation service returned no translation")
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error translating text: ${e.message}")
                return@withContext Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            return@withContext Result.failure(e)
        }
    }

    /**
     * Create a demo translation for testing when network is unavailable
     */
    private fun createDemoTranslation(text: String, targetLanguage: String): String {
        return when (targetLanguage) {
            "he" -> {
                // English to Hebrew demo translation
                when {
                    text.contains("information", ignoreCase = true) -> "המידע בספר זה מיועד למטרות חינוכיות בלבד"
                    text.contains("author", ignoreCase = true) -> "המחבר והמוציא לאור אינם אחראים לכל נזק"
                    text.contains("case studies", ignoreCase = true) -> "כל מקרי הבוחן והתיאורים הם בדיוניים"
                    text.contains("purchaser", ignoreCase = true) -> "כרוכש הספר הדיגיטלי הזה, ניתנות לך זכויות מוגבלות"
                    text.length > 100 -> "תרגום דמו: ${text.take(30)}... [תרגום מלא יהיה זמין עם חיבור לאינטרנט]"
                    else -> "תרגום דמו: $text"
                }
            }
            "en" -> {
                // Hebrew to English demo translation
                when {
                    text.contains("כוח", ignoreCase = true) -> "The Power of Intention - Demo Translation"
                    text.contains("מידע", ignoreCase = true) -> "Information - Demo Translation"
                    text.length > 100 -> "Demo translation: ${text.take(30)}... [Full translation available with internet connection]"
                    else -> "Demo translation: $text"
                }
            }
            else -> "Demo translation to $targetLanguage: $text"
        }
    }
}
