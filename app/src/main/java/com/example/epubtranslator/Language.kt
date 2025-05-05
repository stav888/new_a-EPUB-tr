package com.example.epubtranslator

data class Language(
    val code: String,
    val name: String,
    var isDownloaded: Boolean = false,
    var isDownloading: Boolean = false,
    var downloadProgress: Int = 0
)
