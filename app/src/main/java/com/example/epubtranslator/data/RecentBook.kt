package com.example.epubtranslator.data

import java.io.File
import java.util.Date

/**
 * Data class representing a recently opened EPUB book
 */
data class RecentBook(
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val lastOpenedDate: Date = Date(),
    val coverImagePath: String? = null,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Get the file name from the path
     */
    fun getFileName(): String {
        return File(filePath).name
    }

    /**
     * Check if the file exists
     */
    fun fileExists(): Boolean {
        return File(filePath).exists()
    }

    /**
     * Get a formatted date string
     */
    fun getFormattedDate(): String {
        val now = System.currentTimeMillis()
        val diff = now - lastOpenedTimestamp

        return when {
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)} minutes ago"
            diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)} hours ago"
            diff < 7 * 24 * 60 * 60 * 1000 -> "${diff / (24 * 60 * 60 * 1000)} days ago"
            else -> "${diff / (7 * 24 * 60 * 60 * 1000)} weeks ago"
        }
    }
}
