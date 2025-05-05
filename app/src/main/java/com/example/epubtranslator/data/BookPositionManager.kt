package com.example.epubtranslator.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Manager class for handling book positions (last page viewed)
 */
class BookPositionManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "book_position_prefs"
        private const val KEY_PREFIX_POSITION = "position_"
        private const val KEY_PREFIX_SCROLL = "scroll_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
}
