package com.example.epubtranslator.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        return try {
            val json = prefs.getString(KEY_RECENT_BOOKS, null) ?: return emptyList()
            val type = object : TypeToken<List<RecentBook>>() {}.type
            val books: List<RecentBook> = gson.fromJson(json, type) ?: emptyList()

            // Filter out books that no longer exist and sort by last opened timestamp (newest first)
            books.filter { it.fileExists() }.sortedByDescending { it.lastOpenedTimestamp }
        } catch (e: Exception) {
            Log.e("RecentBooksManager", "Error loading recent books", e)
            // Do NOT clear stored data on transient errors; return empty list and keep data intact
            emptyList()
        }
    }

    /**
     * Add a book to the recent books list or update its timestamp if it already exists
     */
    fun addRecentBook(filePath: String, coverImagePath: String? = null) {
        val file = File(filePath)
        if (!file.exists()) return

        val currentTime = System.currentTimeMillis()

        // Prefer EPUB metadata title if available; fallback to filename
        val metaTitle = extractTitleFromEpub(filePath)
        val title = if (!metaTitle.isNullOrBlank()) metaTitle else file.nameWithoutExtension

        val recentBooks = getRecentBooks().toMutableList()

        // Check if the book already exists
        val existingBookIndex = recentBooks.indexOfFirst { it.filePath == filePath }

        if (existingBookIndex != -1) {
            // Update the existing book's timestamp
            val existingBook = recentBooks[existingBookIndex]
            val updatedCover = coverImagePath ?: existingBook.coverImagePath
            Log.d("RecentBooksManager", "Updating existing book ${existingBook.title} coverPath=$updatedCover")
            val updatedBook = existingBook.copy(
                lastOpenedDate = Date(),
                lastOpenedTimestamp = currentTime,
                coverImagePath = updatedCover
            )
            // Remove the old entry and add the updated one at the beginning
            recentBooks.removeAt(existingBookIndex)
            recentBooks.add(0, updatedBook)
        } else {
            // Create a new book entry
            Log.d("RecentBooksManager", "Adding new book $title coverPath=$coverImagePath")
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
        Log.d("RecentBooksManager", "Saved ${recentBooks.size} books. First cover=${recentBooks.firstOrNull()?.coverImagePath}")
    }

    private fun extractTitleFromEpub(epubPath: String): String? {
        return try {
            val zip = java.util.zip.ZipFile(epubPath)
            val container = zip.entries().toList().find { it.name == "META-INF/container.xml" }
            if (container != null) {
                val containerXml = zip.getInputStream(container).bufferedReader().use { it.readText() }
                val opfPath = extractOpfPath(containerXml)
                if (!opfPath.isNullOrBlank()) {
                    val opfEntry = zip.entries().toList().find { it.name == opfPath }
                    if (opfEntry != null) {
                        val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                        val patterns = listOf(
                            "<dc:title[^>]*>(.*?)</dc:title>".toRegex(RegexOption.IGNORE_CASE),
                            "<title[^>]*>(.*?)</title>".toRegex(RegexOption.IGNORE_CASE)
                        )
                        for (p in patterns) {
                            val m = p.find(opfXml)
                            if (m != null) {
                                val t = m.groupValues[1].trim()
                                if (t.isNotEmpty()) return t
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractOpfPath(containerXml: String): String? {
        val patterns = listOf(
            "<rootfile[^>]+full-path=\"([^\"]+)\"[^>]*media-type=\"application/oebps-package\\+xml\"[^>]*>".toRegex(),
            "<rootfile[^>]+media-type=\"application/oebps-package\\+xml\"[^>]*full-path=\"([^\"]+)\"[^>]*>".toRegex(),
            "<rootfile[^>]*full-path=\"([^\"]+)\"[^>]*>".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(containerXml)
            if (match != null) return match.groupValues[1]
        }
        return null
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
