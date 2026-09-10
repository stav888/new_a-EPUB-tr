package com.example.epubtranslator.translation

/**
 * Data class for offline language management
 */
data class OfflineLanguage(
    val code: String,
    val name: String,
    val mlKitCode: String,
    var isDownloaded: Boolean = false,
    var isDownloading: Boolean = false,
    var downloadProgress: Int = 0
)
