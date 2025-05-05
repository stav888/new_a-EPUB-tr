package com.example.epubtranslator.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Date

/**
 * Manager class for handling recently opened EPUB books
 */
class RecentBooksManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "recent_books_prefs"
        private const val KEY_RECENT_BOOKS = "recent_books"
        private const val MAX_RECENT_BOOKS = 10
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Get the list of recently opened books
     */
    fun getRecentBooks(): List<RecentBook> {
        val json = prefs.getString(KEY_RECENT_BOOKS, null) ?: return emptyList()
        val type = object : TypeToken<List<RecentBook>>() {}.type
        val books: List<RecentBook> = gson.fromJson(json, type)

        // Filter out books that no longer exist and sort by last opened timestamp (newest first)
        return books.filter { it.fileExists() }.sortedByDescending { it.lastOpenedTimestamp }
    }

    /**
     * Add a book to the recent books list or update its timestamp if it already exists
     */
    fun addRecentBook(filePath: String, coverImagePath: String? = null) {
        val file = File(filePath)
        if (!file.exists()) return

        val title = file.nameWithoutExtension
        val currentTime = System.currentTimeMillis()

        val recentBooks = getRecentBooks().toMutableList()

        // Check if the book already exists
        val existingBookIndex = recentBooks.indexOfFirst { it.filePath == filePath }

        if (existingBookIndex != -1) {
            // Update the existing book's timestamp
            val existingBook = recentBooks[existingBookIndex]
            val updatedBook = existingBook.copy(
                lastOpenedDate = Date(),
                lastOpenedTimestamp = currentTime,
                coverImagePath = coverImagePath ?: existingBook.coverImagePath
            )
            // Remove the old entry and add the updated one at the beginning
            recentBooks.removeAt(existingBookIndex)
            recentBooks.add(0, updatedBook)
        } else {
            // Create a new book entry
            val recentBook = RecentBook(
                title = title,
                filePath = filePath,
                lastOpenedDate = Date(),
                coverImagePath = coverImagePath,
                lastOpenedTimestamp = currentTime
            )
            // Add the new book at the beginning of the list
            recentBooks.add(0, recentBook)
        }

        // Limit the number of recent books
        if (recentBooks.size > MAX_RECENT_BOOKS) {
            // Remove the last book (oldest in the list)
            recentBooks.removeAt(recentBooks.size - 1)
        }

        // Save the updated list
        saveRecentBooks(recentBooks)
    }

    /**
     * Save the list of recent books to SharedPreferences
     */
    private fun saveRecentBooks(books: List<RecentBook>) {
        val json = gson.toJson(books)
        prefs.edit().putString(KEY_RECENT_BOOKS, json).apply()
    }

    /**
     * Remove a book from the recent books list
     */
    fun removeRecentBook(filePath: String) {
        val recentBooks = getRecentBooks().toMutableList()
        recentBooks.removeIf { it.filePath == filePath }
        saveRecentBooks(recentBooks)
    }

    /**
     * Clear all recent books
     */
    fun clearRecentBooks() {
        prefs.edit().remove(KEY_RECENT_BOOKS).apply()
    }
}
