package com.example.epubtranslator.translation

/**
 * Enum class representing supported languages for translation
 */
enum class Language(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    RUSSIAN("ru", "Russian"),
    CHINESE("zh", "Chinese"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    ARABIC("ar", "Arabic"),
    HEBREW("he", "Hebrew"),
    HINDI("hi", "Hindi");

    companion object {
        fun fromCode(code: String): Language {
            return values().find { it.code == code } ?: ENGLISH
        }
    }
}
