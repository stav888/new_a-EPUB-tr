package com.example.epubtranslator.translation

/** Small local heuristic used to select an ML Kit source language. */
class TranslationService {
    fun detectLanguagePublic(text: String): String {
        val hebrewCount = text.count { it in '\u0590'..'\u05FF' || it in '\uFB1D'..'\uFB4F' }
        val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (hebrewCount > latinCount) "he" else "en"
    }
}
