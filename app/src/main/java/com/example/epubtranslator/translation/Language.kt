package com.example.epubtranslator.translation

/**
 * Enum class representing supported languages for translation
 */
enum class Language(val code: String, val displayName: String, val mlKitCode: String) {
    ENGLISH("en", "English", "en"),
    SPANISH("es", "Spanish", "es"),
    FRENCH("fr", "French", "fr"),
    GERMAN("de", "German", "de"),
    ITALIAN("it", "Italian", "it"),
    PORTUGUESE("pt", "Portuguese", "pt"),
    RUSSIAN("ru", "Russian", "ru"),
    CHINESE("zh", "Chinese", "zh"),
    JAPANESE("ja", "Japanese", "ja"),
    KOREAN("ko", "Korean", "ko"),
    ARABIC("ar", "Arabic", "ar"),
    HEBREW("he", "Hebrew", "he"),
    HINDI("hi", "Hindi", "hi");

    companion object {
        fun fromCode(code: String): Language {
            return values().find { it.code == code } ?: ENGLISH
        }
    }
}
