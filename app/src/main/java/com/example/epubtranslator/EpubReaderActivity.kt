package com.example.epubtranslator

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.widget.LinearLayout
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color

import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import com.example.epubtranslator.data.BookPositionManager
import com.example.epubtranslator.databinding.ActivityEpubReaderBinding
import com.example.epubtranslator.translation.TranslationApi
import com.example.epubtranslator.translation.TranslationManager
import com.example.epubtranslator.translation.TranslationDialog
import com.example.epubtranslator.translation.TranslationMethod
import com.example.epubtranslator.translation.ModelNotDownloadedException
import android.widget.PopupMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

class EpubReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EPUB_FILE_PATH = "epub_file_path"
        private const val JS_INTERFACE_NAME = "AndroidTranslator"
        private const val TAG = "EpubReaderActivity"
    }

    // Track whether the bars (toolbar/navigation) are visible
    private var barsVisible: Boolean = true

    private lateinit var binding: ActivityEpubReaderBinding
    private lateinit var translationManager: TranslationManager
    private lateinit var bookPositionManager: BookPositionManager
    private var epubFilePath: String? = null
    private var currentPage = 0
    private var totalPages = 0
    private var htmlFiles = mutableListOf<String>()
    private var htmlFileNames = mutableListOf<String>() // Store original file names for TOC ordering


    // Map of spine file paths to human-friendly chapter titles parsed from EPUB TOC (preserve insertion order)
    private val tocTitleMap: LinkedHashMap<String, String> = LinkedHashMap()
    private val tocTargetMap: LinkedHashMap<String, String> = LinkedHashMap()

    // Reading preferences
    private var currentFontSize = 100 // Default font size percentage
    private var isDarkMode = false

    // RTL/LTR book direction detection
    private var isRTLBook = false

    // Pending search highlight to apply after page loads
    private var pendingSearchHighlight: String? = null

    // Inline search state
    private var currentSearchIndex = 0
    private var totalSearchResults = 0
    private var creditsCodeDialogShown = false
    private data class ReaderLocation(val pageIndex: Int, val scrollY: Int)
    private val navigationHistory = ArrayDeque<ReaderLocation>()
    private var restoringNavigationHistory = false
    private var pendingAnchor: String? = null
    private var pendingScrollPosition: Int? = null
    // Translation synchronization to prevent race conditions
    private val translationLock = Any()
    private val pendingTranslations = mutableSetOf<String>()

    // Activity Result Launcher for search
    private val searchActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { intent ->
                val shouldNavigate = intent.getBooleanExtra(SimpleSearchActivity.RESULT_NAVIGATE_TO_PAGE, false)
                if (shouldNavigate) {
                    val pageIndex = intent.getIntExtra(SimpleSearchActivity.RESULT_PAGE_INDEX, 0)
                    val searchQuery = intent.getStringExtra(SimpleSearchActivity.RESULT_SEARCH_QUERY) ?: ""

                    // Remember query to highlight after the page loads
                    pendingSearchHighlight = searchQuery

                    // Navigate to the selected page
                    loadPage(pageIndex)

                    // Show a toast with the search result
                    Toast.makeText(this, "Found: \"$searchQuery\"", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpubReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)


        // Restore bars visibility from savedInstanceState or default to true
        barsVisible = savedInstanceState?.getBoolean("barsVisible", true) ?: true

        // Set up toggle buttons for hide/show bars
        setupToggleButtons()

        // Get the EPUB file path from intent
        epubFilePath = intent.getStringExtra(EXTRA_EPUB_FILE_PATH)
        if (epubFilePath == null) {
            finish()
            return
        }

        // Load saved reading preferences early so they apply before WebView setup
        loadReadingPreferences()

        // Initialize managers
        translationManager = TranslationManager(this)
        bookPositionManager = BookPositionManager(this)

        // Set up WebView (will apply font size from preferences inside)
        setupWebView()

        // Update progress as user scrolls within the page
        binding.webView.setOnScrollChangeListener { _, _, _, _, _ ->
            updateScrollProgress()
        }
        // Initialize progress at load time
        binding.webView.post { updateScrollProgress() }

        // Set up gesture detector for swipe navigation
        setupGestureDetector()

        // Set up toolbar controls (updates UI state)
        setupToolbar()

        // Set up translation method dropdown
        setupTranslationMethodDropdown()

        binding.creditsTextView.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.translation_credits)
                .setMessage(R.string.translation_credits_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        val savedCredits = getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
            .getInt("translation_credits", 200)
        updateCreditsDisplay(savedCredits)

        // Set book title in header (prefer metadata title; fallback to filename)
        epubFilePath?.let { path ->
            val metaTitle = extractBookTitleFromEpub(path)
            val displayTitle = if (!metaTitle.isNullOrBlank()) metaTitle else File(path).nameWithoutExtension
            binding.bookTitleText.text = displayTitle
        }


        // Navigation buttons have been removed

        // Inline translation has been removed

        // Detect book reading direction (RTL/LTR) before loading
        epubFilePath?.let { path ->
            isRTLBook = detectBookReadingDirection(path)
            Log.d(TAG, "📖 Book reading direction: ${if (isRTLBook) "RTL" else "LTR"}")
        }


        // Apply bottom navigation layout direction and button order based on book direction
        applyNavigationLayoutDirection()

        // Restore state if available
        restoreInstanceState(savedInstanceState)

        // Load the EPUB file
        loadEpubFile()

        // Set up back pressed callback
        setupBackPressedCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        try {
            Log.d(TAG, "Saving instance state...")

            // Save current page
            outState.putInt("currentPage", currentPage)
            Log.d(TAG, "Saved current page: $currentPage")

            // Save translation state for all pages
            val translationData = HashMap<String, HashMap<String, String>>()
            pageTranslations.forEach { (page, translations) ->
                translationData["page_$page"] = HashMap(translations)
            }
            outState.putSerializable("pageTranslations", translationData)
            Log.d(TAG, "Saved translations for ${translationData.size} pages")

            // Save visible translation state for all pages
            val visibleTranslationData = HashMap<String, HashSet<String>>()
            pageVisibleTranslations.forEach { (page, visibleSet) ->
                visibleTranslationData["page_$page"] = HashSet(visibleSet)
            }
            outState.putSerializable("pageVisibleTranslations", visibleTranslationData)
            Log.d(TAG, "Saved visible translations for ${visibleTranslationData.size} pages")

            // Save target languages for all pages
            val targetLanguageData = HashMap<String, HashMap<String, String>>()
            pageTranslationTargetLanguages.forEach { (page, languages) ->
                targetLanguageData["page_$page"] = HashMap(languages)
            }
            outState.putSerializable("pageTranslationTargetLanguages", targetLanguageData)
            Log.d(TAG, "Saved target languages for ${targetLanguageData.size} pages")

            // Save scroll positions for all pages
            val scrollPositionData = HashMap<String, Int>()
            pageScrollPositions.forEach { (page, scrollY) ->
                scrollPositionData["page_$page"] = scrollY
            }
            outState.putSerializable("pageScrollPositions", scrollPositionData)
            Log.d(TAG, "Saved scroll positions for ${scrollPositionData.size} pages")

            // Save current scroll position
            val currentScrollY = binding.webView.scrollY
            outState.putInt("currentScrollY", currentScrollY)
            Log.d(TAG, "Saved current scroll position: $currentScrollY")

            // Save reading preferences
            outState.putInt("currentFontSize", currentFontSize)
            outState.putBoolean("isDarkMode", isDarkMode)
            Log.d(TAG, "Saved reading preferences: fontSize=$currentFontSize, darkMode=$isDarkMode")

            // Save bars visibility state
            outState.putBoolean("barsVisible", barsVisible)
            Log.d(TAG, "Saved barsVisible: $barsVisible")

            Log.d(TAG, "Instance state saved successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error saving instance state", e)
        }
    }

    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        savedInstanceState?.let { bundle ->
            try {
                Log.d(TAG, "Restoring instance state...")

                // Restore current page
                currentPage = bundle.getInt("currentPage", 0)
                Log.d(TAG, "Restored current page: $currentPage")

                // Restore translation state for all pages
                @Suppress("UNCHECKED_CAST")
                val translationData = bundle.getSerializable("pageTranslations") as? HashMap<String, HashMap<String, String>>
                translationData?.forEach { (pageKey, translations) ->
                    val page = pageKey.removePrefix("page_").toIntOrNull() ?: return@forEach
                    pageTranslations[page] = mutableMapOf<String, String>().apply { putAll(translations) }
                }
                Log.d(TAG, "Restored translations for ${pageTranslations.size} pages")

                // Restore visible translation state for all pages
                @Suppress("UNCHECKED_CAST")
                val visibleTranslationData = bundle.getSerializable("pageVisibleTranslations") as? HashMap<String, HashSet<String>>
                visibleTranslationData?.forEach { (pageKey, visibleSet) ->
                    val page = pageKey.removePrefix("page_").toIntOrNull() ?: return@forEach
                    pageVisibleTranslations[page] = mutableSetOf<String>().apply { addAll(visibleSet) }
                }
                Log.d(TAG, "Restored visible translations for ${pageVisibleTranslations.size} pages")

                // Restore target languages for all pages
                @Suppress("UNCHECKED_CAST")
                val targetLanguageData = bundle.getSerializable("pageTranslationTargetLanguages") as? HashMap<String, HashMap<String, String>>
                targetLanguageData?.forEach { (pageKey, languages) ->
                    val page = pageKey.removePrefix("page_").toIntOrNull() ?: return@forEach
                    pageTranslationTargetLanguages[page] = mutableMapOf<String, String>().apply { putAll(languages) }
                }
                Log.d(TAG, "Restored target languages for ${pageTranslationTargetLanguages.size} pages")

                // Restore scroll positions for all pages
                @Suppress("UNCHECKED_CAST")
                val scrollPositionData = bundle.getSerializable("pageScrollPositions") as? HashMap<String, Int>
                scrollPositionData?.forEach { (pageKey, scrollY) ->
                    val page = pageKey.removePrefix("page_").toIntOrNull() ?: return@forEach
                    pageScrollPositions[page] = scrollY
                }
                Log.d(TAG, "Restored scroll positions for ${pageScrollPositions.size} pages")

                // Restore reading preferences
                currentFontSize = bundle.getInt("currentFontSize", 100)
                isDarkMode = bundle.getBoolean("isDarkMode", false)
                Log.d(TAG, "Restored reading preferences: fontSize=$currentFontSize, darkMode=$isDarkMode")

                // Mark that we have restored state (will be used after WebView loads)
                val currentScrollY = bundle.getInt("currentScrollY", 0)
                if (currentScrollY > 0) {
                    // Store scroll position to restore after page loads
                    pageScrollPositions[currentPage] = currentScrollY
                    Log.d(TAG, "Marked scroll position for restoration: $currentScrollY")
                }

                Log.d(TAG, "Instance state restored successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring instance state", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Save position when activity is paused
        saveCurrentPosition()

        // Save all translations to persistent storage
        saveAllTranslations()
    }

    override fun onStop() {
        super.onStop()
        // Save position when activity is stopped
        saveCurrentPosition()

        // Save all translations to persistent storage
        saveAllTranslations()
    }

    private fun saveCurrentPosition() {
        try {
            epubFilePath?.let { path ->
                // Save the current page number
                bookPositionManager.savePosition(path, currentPage)

                // Save the current scroll position
                val scrollY = binding.webView.scrollY

                // Save to the page-specific map in memory
                pageScrollPositions[currentPage] = scrollY

                // Save to both page-specific and global persistent storage
                bookPositionManager.saveScrollPosition("${path}_page_$currentPage", scrollY)
                bookPositionManager.saveScrollPosition(path, scrollY)

                Log.d(TAG, "Saved scroll position for page $currentPage: $scrollY (memory and persistent storage)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving current position", e)
        }
    }

    private fun loadEpubFile() {
        // Use a small toast to indicate loading has started
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Extract HTML files from EPUB in background thread with optimized processing
                val files = withContext(Dispatchers.IO) {
                    extractHtmlFilesFromEpub(epubFilePath ?: "")
                }

                htmlFiles.addAll(files)
                totalPages = htmlFiles.size

                // Enhanced logging for debugging
                Log.d(TAG, "📊 EPUB Loading Summary:")
                Log.d(TAG, "📊 - HTML files extracted: ${files.size}")
                Log.d(TAG, "📊 - Total pages set: $totalPages")
                Log.d(TAG, "📊 - htmlFiles.size: ${htmlFiles.size}")

                // Log first few and last few filenames for verification
                if (htmlFiles.isNotEmpty()) {
                    Log.d(TAG, "📊 First 3 pages preview:")
                    htmlFiles.take(3).forEachIndexed { index, content ->
                        val preview = content.take(100).replace("\n", " ").trim()
                        Log.d(TAG, "📊   Page ${index + 1}: ${preview}...")
                    }

                    if (htmlFiles.size > 3) {
                        Log.d(TAG, "📊 Last page preview:")
                        val lastIndex = htmlFiles.size - 1
                        val lastContent = htmlFiles[lastIndex].take(100).replace("\n", " ").trim()
                        Log.d(TAG, "📊   Page ${lastIndex + 1}: ${lastContent}...")
                    }
                }

                if (htmlFiles.isNotEmpty()) {
                    // Check if we successfully parsed the EPUB spine for correct ordering
                    val hasCorrectOrdering = checkIfSpineOrderWasUsed()

                    // Get the last saved position
                    epubFilePath?.let { path ->
                        val savedPosition = bookPositionManager.getPosition(path)

                        // If we now have correct ordering but previously didn't, clear old positions
                        // to ensure books start from the beginning with the correct page order
                        if (hasCorrectOrdering && !wasSpineOrderUsedBefore(path)) {
                            Log.d(TAG, "EPUB now has correct spine ordering, clearing old positions to start fresh")
                            bookPositionManager.clearPosition(path)
                            markSpineOrderAsUsed(path)
                            currentPage = 0
                        } else {
                            currentPage = if (savedPosition >= 0 && savedPosition < totalPages) {
                                savedPosition
                            } else {
                                0
                            }
                        }
                    }

                    // Preload all saved scroll positions
                    preloadScrollPositions()

                    // Preload all translations for better performance
                    preloadTranslations()

                    // Load the saved page
                    loadPage(currentPage)
                } else {
                    Toast.makeText(this@EpubReaderActivity, R.string.error_loading_epub, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Toast.makeText(this@EpubReaderActivity, R.string.error_loading_epub, Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Error loading EPUB", e)
                e.printStackTrace()
                finish()
            }
        }
    }

    private fun extractHtmlFilesFromEpub(epubPath: String): List<String> {
        val htmlContents = mutableListOf<String>()

        try {
            Log.d(TAG, "Extracting EPUB: $epubPath")
            val zipFile = ZipFile(epubPath)
            val entries = zipFile.entries()

            // First pass: collect all HTML/XHTML files and image files
            val htmlFiles = mutableListOf<ZipEntry>()
            val imageFiles = mutableListOf<ZipEntry>()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".html") || name.endsWith(".xhtml")) {
                        htmlFiles.add(entry)
                    } else if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                               name.endsWith(".png") || name.endsWith(".gif") ||
                               name.endsWith(".svg") || name.endsWith(".webp")) {
                        imageFiles.add(entry)
                    }
                }
            }

            Log.d(TAG, "Found ${htmlFiles.size} HTML files and ${imageFiles.size} image files")
            Log.d(TAG, "Image files found: ${imageFiles.map { it.name }}")

            // Extract images to cache directory
            val imageCache = File(cacheDir, "epub_images")
            if (!imageCache.exists()) {
                imageCache.mkdirs()
            }

            val imageEntries = extractImagesToCache(zipFile, imageFiles, imageCache)

            // Determine correct reading order using EPUB spine
            val allEntriesList = zipFile.entries().toList()
            val spineOrderPaths = parseEpubSpine(zipFile, allEntriesList)

            // Build ordered list of HTML entries
            val entryByName = htmlFiles.associateBy { normalizeEpubPath(it.name) }
            val orderedEntries = mutableListOf<ZipEntry>()
            val added = mutableSetOf<String>()

            if (spineOrderPaths.isNotEmpty()) {
                for (path in spineOrderPaths) {
                    val entry = entryByName[normalizeEpubPath(path)]
                    if (entry != null) {
                        orderedEntries.add(entry)
                        added.add(normalizeEpubPath(entry.name))
                    }
                }
                // Append any remaining HTML files that weren't in the spine (front/back matter)
                htmlFiles.filter { normalizeEpubPath(it.name) !in added }.sortedBy { it.name }.forEach { orderedEntries.add(it) }
                Log.d(TAG, "Using spine-defined reading order with ${orderedEntries.size} items")
            } else {
                // Fallback: alphabetical order
                orderedEntries.addAll(htmlFiles.sortedBy { it.name })
                Log.w(TAG, "Spine order not found, falling back to alphabetical order")
            }

            // Reset and populate filename list in the same order for TOC
            this@EpubReaderActivity.htmlFileNames.clear()

            // Process each HTML file in the computed order
            for (entry in orderedEntries) {
                try {
                    val content = readZipEntry(zipFile, entry)

                    // Process HTML content to fix image references
                    val processedContent = processHtmlContent(content, entry.name, imageCache, imageEntries)

                    val cleanContent = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset=\"UTF-8\">
                            <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                            <style>
                                body {
                                    font-family: Arial, sans-serif;
                                    line-height: 1.6;
                                    padding: 20px;
                                    margin: 0;
                                }
                                p, div, h1, h2, h3, h4, h5, h6 {
                                    margin-bottom: 1em;
                                    background-color: transparent !important;
                                }

                                /* Ensure no unwanted backgrounds on any elements */
                                * {
                                    background-color: inherit;
                                }

                                /* Reset any persistent hover/active states */
                                p, div, span, h1, h2, h3, h4, h5, h6, li, td, th {
                                    background-color: transparent !important;
                                }
                                img {
                                    max-width: 100%;
                                    height: auto;
                                    display: block;
                                    margin: 10px auto;
                                    object-fit: contain;
                                    border-radius: 4px;
                                }
                            </style>
                        </head>
                        <body>
                            $processedContent
                        </body>
                        </html>
                    """.trimIndent()

                    htmlContents.add(cleanContent)
                    this@EpubReaderActivity.htmlFileNames.add(entry.name)
                    Log.d(TAG, "Processed (ordered): ${entry.name}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing ${entry.name}", e)
                    // Add error page instead of failing completely
                    htmlContents.add("""
                        <html><body>
                        <h1>Error Loading Page</h1>
                        <p>Could not load: ${entry.name}</p>
                        <p>Error: ${e.message}</p>
                        </body></html>
                    """)
                }
            }

            zipFile.close()
            Log.d(TAG, "Successfully extracted ${htmlContents.size} pages (ordered)")

        } catch (e: Exception) {
            Log.e(TAG, "Critical error extracting EPUB", e)
            // Return at least one page so the app doesn't crash
            htmlContents.add("""
                <html><body>
                <h1>Error Loading Book</h1>
                <p>Could not load the EPUB file.</p>
                <p>Error: ${e.message}</p>
                </body></html>
            """)
        }

        return htmlContents
    }

    /**
     * Extract images from EPUB to cache directory
     */
    private fun extractImagesToCache(zipFile: ZipFile, imageFiles: List<ZipEntry>, imageCache: File): Map<String, String> {
        val imageEntries = mutableMapOf<String, String>()

        try {
            for (entry in imageFiles) {
                try {
                    // Create a safe filename for the cached image
                    val safeFileName = entry.name.replace("/", "_").replace("\\", "_")
                    val imageFile = File(imageCache, safeFileName)

                    // Extract image to cache
                    zipFile.getInputStream(entry).use { input ->
                        imageFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Map the original path to the cached file path
                    imageEntries[entry.name] = imageFile.absolutePath
                    Log.d(TAG, "Extracted image: ${entry.name} -> ${imageFile.absolutePath}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting image ${entry.name}", e)
                }
            }

            Log.d(TAG, "Successfully extracted ${imageEntries.size} images to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting images to cache", e)
        }

        return imageEntries
    }

    private fun readZipEntry(zipFile: ZipFile, entry: ZipEntry): String {
        return zipFile.getInputStream(entry).use { inputStream ->
            inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
        }
    }

    // Extract book title from EPUB metadata (dc:title) using container.xml -> OPF
    private fun extractBookTitleFromEpub(epubPath: String): String? {
        return try {
            val zip = ZipFile(epubPath)
            try {
                val containerEntry = zip.entries().toList().find { it.name == "META-INF/container.xml" }
                if (containerEntry != null) {
                    val containerXml = readZipEntry(zip, containerEntry)
                    val opfPath = extractOpfPath(containerXml)
                    if (!opfPath.isNullOrBlank()) {
                        val opfEntry = zip.entries().toList().find { it.name == opfPath }
                        if (opfEntry != null) {
                            val opfXml = readZipEntry(zip, opfEntry)
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
            } finally {
                zip.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detect book reading direction (RTL/LTR) from EPUB metadata
     */
    private fun detectBookReadingDirection(epubPath: String): Boolean {
        return try {
            val zip = ZipFile(epubPath)
            try {
                val containerEntry = zip.entries().toList().find { it.name == "META-INF/container.xml" }
                if (containerEntry != null) {
                    val containerXml = readZipEntry(zip, containerEntry)
                    val opfPath = extractOpfPath(containerXml)
                    if (!opfPath.isNullOrBlank()) {
                        val opfEntry = zip.entries().toList().find { it.name == opfPath }
                        if (opfEntry != null) {
                            val opfXml = readZipEntry(zip, opfEntry)

                            // Check for RTL language codes in dc:language
                            val languagePattern = "<dc:language[^>]*>(.*?)</dc:language>".toRegex(RegexOption.IGNORE_CASE)
                            val languageMatch = languagePattern.find(opfXml)
                            if (languageMatch != null) {
                                val language = languageMatch.groupValues[1].trim().lowercase()
                                // RTL languages: Hebrew (he/iw), Arabic (ar), Persian (fa), Urdu (ur), Yiddish (yi)
                                if (language.startsWith("he") || language.startsWith("iw") ||
                                    language.startsWith("ar") || language.startsWith("fa") ||
                                    language.startsWith("ur") || language.startsWith("yi")) {
                                    Log.d(TAG, "📖 Detected RTL language: $language")
                                    return true
                                }
                            }

                            // Check for page-progression-direction attribute
                            val ppdPattern = "page-progression-direction\\s*=\\s*[\"']rtl[\"']".toRegex(RegexOption.IGNORE_CASE)
                            if (ppdPattern.find(opfXml) != null) {
                                Log.d(TAG, "📖 Detected RTL from page-progression-direction")
                                return true
                            }
                        }
                    }
                }
                false
            } finally {
                zip.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting book reading direction", e)
            false
        }
    }


    /**
     * Set up toolbar controls for font size and theme
     */
    private fun setupToolbar() {
        // Load saved preferences
        loadReadingPreferences()

        // Update UI to reflect current settings
        updateFontSizeDisplay()
        updateThemeIcon()

        // Font size controls
        binding.fontSizeDecreaseButton.setOnClickListener {
            decreaseFontSize()
        }

        binding.fontSizeIncreaseButton.setOnClickListener {
            increaseFontSize()
        }

        // Theme toggle
        binding.themeToggleButton.setOnClickListener {
            toggleTheme()
        }

        // Table of Contents button
        binding.tocButton.setOnClickListener {
            showTableOfContents()
        }

        // Search button
        binding.searchButton.setOnClickListener {
            Log.d(TAG, "🔍 Search button clicked")
            toggleInPageSearch()
        }

        // Settings (gear) button with dropdown actions
        binding.settingsButton.setOnClickListener { anchor ->
            try {
                val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
                popup.menu.add("Clear Book Data").setOnMenuItemClickListener {
                    confirmClearBookData()
                    true
                }
                popup.show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing settings menu", e)
            }
        }

        // Page navigation controls
        setupPageNavigation()
    }

    /**
     * Set up toggle buttons for hiding/showing toolbar and navigation bars
     */
    private fun setupToggleButtons() {
        try {
            // Set initial visibility based on barsVisible state
            setBarsVisibility(barsVisible)

            // Hide button click listener (in bottom navigation bar)
            binding.hideBarButton.setOnClickListener {
                Log.d(TAG, "Hide bars button clicked")
                hideBars()
            }

            // Show button click listener (floating action button)
            binding.showBarButton.setOnClickListener {
                Log.d(TAG, "Show bars button clicked")
                showBars()
            }

            Log.d(TAG, "Toggle buttons setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up toggle buttons", e)
        }
    }

    /**
     * Hide the toolbar and navigation bars with smooth animation
     */
    private fun hideBars() {
        try {
            barsVisible = false

            // Fade the bars while they keep their layout space, avoiding a black gap.
            binding.toolbarLayout.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.toolbarLayout.visibility = View.GONE
                    binding.toolbarLayout.alpha = 1f
                }
                .start()

            // Fade the bottom bar without translating it through the WebView area.
            binding.navigationLayout.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.navigationLayout.visibility = View.GONE
                    binding.navigationLayout.alpha = 1f
                    // Show the floating action button
                    binding.showBarButton.visibility = View.VISIBLE
                    binding.showBarButton.alpha = 0f
                    binding.showBarButton.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start()
                }
                .start()

            Log.d(TAG, "Bars hidden successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding bars", e)
        }
    }

    /**
     * Show the toolbar and navigation bars with smooth animation
     */
    private fun showBars() {
        try {
            barsVisible = true

            // Hide the floating action button first
            binding.showBarButton.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.showBarButton.visibility = View.GONE
                }
                .start()

            // Restore layout space before fading the bars back in.
            binding.toolbarLayout.visibility = View.VISIBLE
            binding.toolbarLayout.translationY = 0f
            binding.toolbarLayout.alpha = 0f
            binding.toolbarLayout.animate()
                .alpha(1f)
                .setDuration(300)
                .start()

            binding.navigationLayout.visibility = View.VISIBLE
            binding.navigationLayout.translationY = 0f
            binding.navigationLayout.alpha = 0f
            binding.navigationLayout.animate()
                .alpha(1f)
                .setDuration(300)
                .start()

            Log.d(TAG, "Bars shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing bars", e)
        }
    }

    private fun toggleBars() {
        if (barsVisible) {
            hideBars()
        } else {
            showBars()
        }
    }

    private fun clearWebViewSelection() {
        binding.webView.evaluateJavascript(
            "window.getSelection && window.getSelection().removeAllRanges();",
            null
        )
    }

    /**
     * Set the visibility of the bars (toolbar/navigation) and toggle buttons
     */
    private fun setBarsVisibility(visible: Boolean) {
        try {
            barsVisible = visible
            if (visible) {
                binding.toolbarLayout.visibility = View.VISIBLE
                binding.toolbarLayout.translationY = 0f
                binding.toolbarLayout.alpha = 1f
                binding.navigationLayout.visibility = View.VISIBLE
                binding.navigationLayout.translationY = 0f
                binding.navigationLayout.alpha = 1f
                binding.showBarButton.visibility = View.GONE
            } else {
                binding.toolbarLayout.visibility = View.GONE
                binding.toolbarLayout.alpha = 1f
                binding.navigationLayout.visibility = View.GONE
                binding.navigationLayout.alpha = 1f
                binding.showBarButton.visibility = View.VISIBLE
            }
            Log.d(TAG, "Bars visibility set to: $visible")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bars visibility", e)
        }
    }

    private fun confirmClearBookData() {
        try {
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear Book Data")
                .setMessage("Clear all translations for this book? This cannot be undone.")
                .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .setPositiveButton("Clear") { dialog, _ ->
                    dialog.dismiss()
                    clearCurrentBookData()
                }
            builder.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing confirmation dialog", e)
        }
    }


    /**
     * Apply RTL/LTR for the bottom navigation and ensure correct button order
     */
    private fun applyNavigationLayoutDirection() {
        try {
            val dir = if (isRTLBook) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR

            // Set layout direction on the entire navigation area (inherits to children)
            binding.navigationLayout.layoutDirection = dir

            // Reorder the center button row to match reading direction expectations
            val row = binding.navigationButtonsRow
            if (row != null) {
                row.removeAllViews()
                if (isRTLBook) {
                    // For RTL: [Last][Next][Previous][First] (visually right→left)
                    row.addView(binding.lastPageButton)
                    row.addView(binding.nextPageButton)
                    row.addView(binding.previousPageButton)
                    row.addView(binding.firstPageButton)
                } else {
                    // For LTR: [First][Previous][Next][Last]
                    row.addView(binding.firstPageButton)
                    row.addView(binding.previousPageButton)
                    row.addView(binding.nextPageButton)
                    row.addView(binding.lastPageButton)
                }
            }

            Log.d(TAG, "Applied navigation layout direction: ${if (isRTLBook) "RTL" else "LTR"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying navigation layout direction", e)
        }
    }


    private fun clearCurrentBookData() {
        try {
            val path = epubFilePath
            // Clear persisted translations for this book
            if (!path.isNullOrEmpty()) {
                try {
                    bookPositionManager.clearBookTranslations(path)
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing persisted translations", e)
                }
            }

            // Clear in-memory translation state
            synchronized(pageTranslations) { pageTranslations.clear() }
            synchronized(pageVisibleTranslations) { pageVisibleTranslations.clear() }
            synchronized(pageTranslationMethods) { pageTranslationMethods.clear() }
            synchronized(pageTranslationTargetLanguages) { pageTranslationTargetLanguages.clear() }
            needToReapplyTranslations = false

            // Reload current page to show original text
            loadPage(currentPage)
            Toast.makeText(this, "Cleared translations for this book", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing current book data", e)
            Toast.makeText(this, "Failed to clear book data", Toast.LENGTH_SHORT).show()
        }
    }


    /**
     * Set up page navigation controls
     */
    private fun setupPageNavigation() {
        try {
            Log.d(TAG, "Setting up page navigation controls")

            binding.undoNavigationButton.setOnClickListener {
                if (navigationHistory.isNotEmpty()) {
                    val previousLocation = navigationHistory.removeLast()
                    restoringNavigationHistory = true
                    pageScrollPositions[previousLocation.pageIndex] = previousLocation.scrollY
                    loadPage(previousLocation.pageIndex)
                }
            }

            // Page navigation slider
            binding.pageNavigationSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    try {
                        if (fromUser && totalPages > 0) {
                            val targetPage = (progress * (totalPages - 1)) / 100
                            if (targetPage != currentPage) {
                                Log.d(TAG, "Navigation slider: moving to page $targetPage")
                                loadPage(targetPage)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in navigation slider progress change", e)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                    Log.d(TAG, "Navigation slider: start tracking touch")
                }

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    Log.d(TAG, "Navigation slider: stop tracking touch")
                }
            })

            // Navigation buttons with RTL/LTR support
            binding.firstPageButton.setOnClickListener {
                try {
                    Log.d(TAG, "First page button clicked (RTL: $isRTLBook)")
                    if (isRTLBook) {
                        // RTL: "First" means rightmost page (last page index)
                        if (totalPages > 0 && currentPage < totalPages - 1) {
                            loadPage(totalPages - 1)
                        }
                    } else {
                        // LTR: "First" means leftmost page (page 0)
                        if (currentPage > 0) {
                            loadPage(0)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in first page button click", e)
                }
            }

            binding.previousPageButton.setOnClickListener {
                try {
                    Log.d(TAG, "Previous page button clicked (RTL: $isRTLBook)")
                    if (isRTLBook) {
                        // RTL: "Previous" means forward in reading (right to left = higher index)
                        if (currentPage < totalPages - 1) {
                            loadPage(currentPage + 1)
                        }
                    } else {
                        // LTR: "Previous" means backward in reading (left to right = lower index)
                        if (currentPage > 0) {
                            loadPage(currentPage - 1)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in previous page button click", e)
                }
            }

            binding.nextPageButton.setOnClickListener {
                try {
                    Log.d(TAG, "Next page button clicked (RTL: $isRTLBook)")
                    if (isRTLBook) {
                        // RTL: "Next" means backward in reading (right to left = lower index)
                        if (currentPage > 0) {
                            loadPage(currentPage - 1)
                        }
                    } else {
                        // LTR: "Next" means forward in reading (left to right = higher index)
                        if (currentPage < totalPages - 1) {
                            loadPage(currentPage + 1)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in next page button click", e)
                }
            }

            binding.lastPageButton.setOnClickListener {
                try {
                    Log.d(TAG, "Last page button clicked (RTL: $isRTLBook)")
                    if (isRTLBook) {
                        // RTL: "Last" means leftmost page (page 0)
                        if (currentPage > 0) {
                            loadPage(0)
                        }
                    } else {
                        // LTR: "Last" means rightmost page (last page index)
                        if (totalPages > 0 && currentPage < totalPages - 1) {
                            loadPage(totalPages - 1)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in last page button click", e)
                }
            }

            // Update navigation state
            updateNavigationControls()
            Log.d(TAG, "Page navigation setup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Critical error setting up page navigation", e)
            // Don't crash the app, just log the error
        }
    }

    /**
     * Update navigation controls state
     */
    private fun updateNavigationControls() {
        try {
            Log.d(TAG, "Updating navigation controls: currentPage=$currentPage, totalPages=$totalPages")

            // Update slider
            if (totalPages > 0) {
                val progress = (currentPage * 100) / (totalPages - 1).coerceAtLeast(1)
                binding.pageNavigationSlider.progress = progress
                binding.pageNavigationSlider.max = 100
                Log.d(TAG, "Updated slider progress to $progress")
            }

            // Update page number display
            val pageText = "${currentPage + 1} / $totalPages"
            binding.pageNumberTextView.text = pageText
            Log.d(TAG, "Updated page number display to: $pageText")

            // Update scroll-based progress for current page
            runCatching { updateScrollProgress() }

            // Update button states
            val canGoBack = currentPage > 0
            val canGoForward = currentPage < totalPages - 1

            binding.firstPageButton.isEnabled = canGoBack
            binding.previousPageButton.isEnabled = canGoBack
            binding.nextPageButton.isEnabled = canGoForward
            binding.lastPageButton.isEnabled = canGoForward

            // Update button alpha for visual feedback
            binding.firstPageButton.alpha = if (canGoBack) 1.0f else 0.5f
            binding.previousPageButton.alpha = if (canGoBack) 1.0f else 0.5f
            binding.nextPageButton.alpha = if (canGoForward) 1.0f else 0.5f
            binding.lastPageButton.alpha = if (canGoForward) 1.0f else 0.5f
            binding.undoNavigationButton.isEnabled = navigationHistory.isNotEmpty()
            binding.undoNavigationButton.alpha = if (navigationHistory.isNotEmpty()) 1.0f else 0.5f

            Log.d(TAG, "Navigation controls updated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating navigation controls", e)
            // Don't crash the app, just log the error
        }
    }

    /**
     * Update the scroll-based reading progress for the current page (1.0% top, 100.0% bottom)
     */
    private fun updateScrollProgress() {
        try {
            // Compute content height in pixels. WebView.contentHeight is in CSS px; multiply by scale to get actual px
            val scale = binding.webView.scale.takeIf { it > 0f } ?: 1f
            val contentHeightPx = (binding.webView.contentHeight * scale).toInt()
            val viewHeightPx = binding.webView.height
            val maxScroll = (contentHeightPx - viewHeightPx).coerceAtLeast(1)
            val scrollY = binding.webView.scrollY.coerceIn(0, maxScroll)

            // Calculate percentage with decimal precision
            val rawPercent = (scrollY.toFloat() / maxScroll.toFloat()) * 100f

            // Ensure progress starts at 1.0% instead of 0.0% and ends at 100.0%
            val adjustedPercent = if (rawPercent <= 0f) {
                1.0f
            } else if (rawPercent >= 100f) {
                100.0f
            } else {
                // Scale from 1.0% to 100.0% based on scroll position
                1.0f + (rawPercent * 99.0f / 100.0f)
            }

            // Format to one decimal place
            binding.readingProgressTextView.text = String.format("%.1f%%", adjustedPercent)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating scroll progress", e)
        }
    }

    /**
     * Load reading preferences from SharedPreferences
     */
    private fun loadReadingPreferences() {
        val prefs = getSharedPreferences("reading_prefs", Context.MODE_PRIVATE)
        currentFontSize = prefs.getInt("font_size", 100)
        isDarkMode = prefs.getBoolean("dark_mode", false)
    }

    /**
     * Save reading preferences to SharedPreferences
     */
    private fun saveReadingPreferences() {
        val prefs = getSharedPreferences("reading_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("font_size", currentFontSize)
            .putBoolean("dark_mode", isDarkMode)
            .apply()
    }

    /**
     * Increase font size
     */
    private fun increaseFontSize() {
        if (currentFontSize < 200) {
            currentFontSize += 10
            updateFontSizeDisplay()
            applyFontSize()
            saveReadingPreferences()
        }
    }

    /**
     * Decrease font size
     */
    private fun decreaseFontSize() {
        if (currentFontSize > 50) {
            currentFontSize -= 10
            updateFontSizeDisplay()
            applyFontSize()
            saveReadingPreferences()
        }
    }

    /**
     * Update font size display
     */
    private fun updateFontSizeDisplay() {
        binding.fontSizeTextView.text = "${currentFontSize}%"
    }

    /**
     * Apply font size to WebView
     */
    private fun applyFontSize() {
        binding.webView.settings.textZoom = currentFontSize
    }

    /**
     * Toggle between dark and light theme
     */
    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        updateThemeIcon()
        applyTheme()
        saveReadingPreferences()
    }

    /**
     * Update theme toggle icon
     */
    private fun updateThemeIcon() {
        val iconRes = if (isDarkMode) {
            R.drawable.ic_light_mode
        } else {
            R.drawable.ic_dark_mode
        }
        binding.themeToggleButton.setImageResource(iconRes)
    }

    /**
     * Apply theme to the reading interface
     */
    private fun applyTheme() {
        val backgroundColor = if (isDarkMode) "#1a1a1a" else "#ffffff"
        val textColor = if (isDarkMode) "#e0e0e0" else "#000000"

        // Don't override toolbar background - let it use theme-aware colors from layout

        // Apply theme to WebView content via JavaScript
        val jsCode = """
            (function() {
                var style = document.getElementById('theme-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'theme-style';
                    document.head.appendChild(style);
                }

                style.textContent = `
                    body {
                        background-color: $backgroundColor !important;
                        color: $textColor !important;
                    }

                    p, div, span, h1, h2, h3, h4, h5, h6, li, td, th {
                        color: $textColor !important;
                    }

                    /* Hover effects removed to avoid gray backgrounds in all themes */

                    /* Ensure backgrounds are transparent by default */
                    p, div, span, h1, h2, h3, h4, h5, h6, li {
                        background-color: transparent !important;
                        transition: background-color 0.1s ease-out;
                    }
                `;
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(jsCode, null)
    }

    /**
     * Show Table of Contents dialog with enhanced chapter detection
     */
    private fun showTableOfContents() {
        Log.d(TAG, "🔍 TOC Debug: Starting TOC generation")
        Log.d(TAG, "🔍 TOC Debug: totalPages = $totalPages")
        Log.d(TAG, "🔍 TOC Debug: htmlFiles.size = ${htmlFiles.size}")
        Log.d(TAG, "🔍 TOC Debug: htmlFileNames.size = ${htmlFileNames.size}")

        // Verify spine order consistency
        Log.d(TAG, "📚 TOC SPINE ORDER: Verifying chapter order consistency:")
        if (htmlFileNames.size == htmlFiles.size) {
            Log.d(TAG, "📚 TOC SPINE ORDER: ✅ File count matches content count")
            htmlFileNames.forEachIndexed { index, fileName ->
                Log.d(TAG, "📚 TOC SPINE ORDER: [$index] $fileName")
            }
        } else {
            Log.w(TAG, "📚 TOC SPINE ORDER: ⚠️ File name count (${htmlFileNames.size}) != content count (${htmlFiles.size})")
        }

        // Run validation and log results
        val validationResult = validateTocConsistency()
        Log.d(TAG, validationResult)

        // Run spine order validation
        val spineValidationResult = validateSpineOrder()
        Log.d(TAG, spineValidationResult)

        // Verify page count consistency
        if (totalPages != htmlFiles.size) {
            Log.w(TAG, "⚠️ TOC Warning: totalPages ($totalPages) != htmlFiles.size (${htmlFiles.size})")
            // Update totalPages to match actual content
            totalPages = htmlFiles.size
            Log.d(TAG, "🔧 TOC Fix: Updated totalPages to $totalPages")
        }

        // Build entries from the actual nav.xhtml/NCX hrefs instead of assuming
        // that every spine page is a TOC item in the same order.
        val tocItems = mutableListOf<String>()
        val tocTargets = mutableListOf<Pair<Int, String?>>()
        val tocSource: List<Pair<String, String>> = if (tocTitleMap.isNotEmpty()) {
            tocTitleMap.entries.map { it.key to it.value }
        } else {
            htmlFileNames.mapIndexed { index, fileName ->
                fileName to extractChapterTitle(htmlFiles[index], index, fileName)
            }
        }

        for ((targetPath, title) in tocSource) {
            val normalizedTarget = normalizeEpubPath(targetPath)
            val exactIndex = htmlFileNames.indexOfFirst { normalizeEpubPath(it) == normalizedTarget }
            val targetIndex = if (exactIndex >= 0) exactIndex else {
                val targetName = normalizedTarget.substringAfterLast('/')
                val matches = htmlFileNames.mapIndexedNotNull { index, fileName ->
                    if (normalizeEpubPath(fileName).substringAfterLast('/') == targetName) index else null
                }
                matches.singleOrNull() ?: -1
            }
            if (targetIndex >= 0) {
                tocItems.add(title)
                tocTargets.add(targetIndex to tocTargetMap[targetPath])
                Log.d(TAG, "🔍 TOC Debug: '$title' -> page ${targetIndex + 1}, href=${tocTargetMap[targetPath]}")
            } else {
                Log.w(TAG, "TOC target not found in spine: $targetPath")
            }
        }

        val actualPageCount = tocItems.size

        Log.d(TAG, "🔍 TOC Debug: Generated ${tocItems.size} TOC items")

        val dialogContext = android.view.ContextThemeWrapper(
            this,
            if (isDarkMode) R.style.ThemeOverlay_EPUBTranslator_TocDialog_Dark
            else R.style.ThemeOverlay_EPUBTranslator_TocDialog_Light
        )
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(dialogContext)
        builder.setTitle("Table of Contents ($actualPageCount pages)")

        val adapter = object : android.widget.ArrayAdapter<String>(
            dialogContext,
            android.R.layout.simple_list_item_1,
            tocItems
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)

                // Get current theme colors
                val typedValue = android.util.TypedValue()
                val theme = context.theme

                // Light blue highlight for current chapter/page
                val tocTargetPage = tocTargets.getOrNull(position)?.first
                if (tocTargetPage == currentPage) {
                    // Use theme-aware highlight color (fallback to a subtle blue)
                    var accent = 0xFF448AFF.toInt() // default Blue A200
                    try {
                        theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
                        if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                            accent = typedValue.data
                        }
                    } catch (_: Exception) {}
                    view.setBackgroundColor(Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)))
                } else {
                    view.setBackgroundColor(Color.TRANSPARENT)
                }

                // Resolve the row text from the active Material theme.
                val tv = view.findViewById<android.widget.TextView>(android.R.id.text1)
                if (tv != null) {
                    val textColors = dialogContext.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorOnSurface))
                    tv.setTextColor(textColors.getColor(0, Color.BLACK))
                    textColors.recycle()
                }
                return view
            }
        }

        builder.setAdapter(adapter) { _, which ->
            Log.d(TAG, "🔍 TOC Debug: User selected page ${which + 1}")

            // Navigate to selected page
            if (which < actualPageCount) {
                val target = tocTargets.getOrNull(which)
                if (target != null) {
                    navigationHistory.addLast(ReaderLocation(currentPage, binding.webView.scrollY))
                    restoringNavigationHistory = true
                    pendingAnchor = null
                    pageScrollPositions.remove(target.first)
                    loadPage(target.first, scrollPositionOverride = 0)
                } else {
                    navigationHistory.addLast(ReaderLocation(currentPage, binding.webView.scrollY))
                    restoringNavigationHistory = true
                    pendingAnchor = null
                    pageScrollPositions.remove(which)
                    loadPage(which, scrollPositionOverride = 0)
                }
                Toast.makeText(this, "Navigated to: ${tocItems[which]}", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "❌ TOC Error: Invalid page selection: $which")
                Toast.makeText(this, "Error: Invalid page selection", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        val dialog = builder.create()
        dialog.show()
    }

    /**
     * Open search activity
     */
    private fun openSearchActivity() {
        try {
            Log.d(TAG, "🔍 Opening search activity...")
            Log.d(TAG, "🔍 Book path: $epubFilePath")
            Log.d(TAG, "🔍 Book title: ${getBookTitle()}")
            Log.d(TAG, "🔍 HTML files count: ${htmlFiles.size}")

            if (htmlFiles.isEmpty()) {
                Log.w(TAG, "🔍 Warning: No HTML files available for search")
                Toast.makeText(this, "No content available for search", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(this, SimpleSearchActivity::class.java).apply {
                putExtra(SimpleSearchActivity.EXTRA_BOOK_PATH, epubFilePath ?: "")
                putExtra(SimpleSearchActivity.EXTRA_BOOK_TITLE, getBookTitle())
                putStringArrayListExtra(SimpleSearchActivity.EXTRA_HTML_FILES, ArrayList(htmlFiles))
            }

            Log.d(TAG, "🔍 Launching search activity...")
            searchActivityLauncher.launch(intent)
            Log.d(TAG, "🔍 Search activity launched successfully with ${htmlFiles.size} HTML files")
        } catch (e: Exception) {
            Log.e(TAG, "🔍 Error launching search activity", e)
            Toast.makeText(this, "Error opening search: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getBookTitle(): String {
        return epubFilePath?.let { path ->
            File(path).nameWithoutExtension
        } ?: "Unknown Book"
    }

    /**
     * Toggle inline search functionality
     */
    private fun toggleInPageSearch() {
        try {
            Log.d(TAG, "Toggling inline search")

            if (binding.inlineSearchLayout.visibility == View.VISIBLE) {
                // Hide search bar
                hideInlineSearch()
            } else {
                // Show search bar
                showInlineSearch()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error toggling inline search", e)
            Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show the inline search bar
     */
    private fun showInlineSearch() {
        binding.inlineSearchLayout.visibility = View.VISIBLE
        binding.inlineSearchEditText.requestFocus()

        // Show keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.inlineSearchEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        // Set up search functionality
        setupInlineSearchFunctionality()
    }

    /**
     * Hide the inline search bar
     */
    private fun hideInlineSearch() {
        binding.inlineSearchLayout.visibility = View.GONE
        binding.searchResultsPreview.visibility = View.GONE
        binding.webView.clearMatches()

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.inlineSearchEditText.windowToken, 0)

        // Clear search text and reset counters
        binding.inlineSearchEditText.text?.clear()
        binding.searchResultsCounter.text = "[0/0]"
        currentSearchIndex = 0
        totalSearchResults = 0
    }

    /**
     * Set up inline search functionality with enhanced features
     */
    private fun setupInlineSearchFunctionality() {
        // Close button
        binding.inlineSearchCloseButton.setOnClickListener {
            hideInlineSearch()
        }

        // Previous/Next navigation buttons
        binding.searchPreviousButton.setOnClickListener {
            navigateSearchResult(-1)
        }

        binding.searchNextButton.setOnClickListener {
            navigateSearchResult(1)
        }

        // Search text watcher
        binding.inlineSearchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString() ?: ""
                if (!creditsCodeMatches(query)) {
                    creditsCodeDialogShown = false
                } else if (!creditsCodeDialogShown) {
                    creditsCodeDialogShown = true
                    binding.inlineSearchEditText.post {
                        binding.inlineSearchEditText.text?.clear()
                        showCreditsEditor()
                    }
                    return
                }
                performInlineSearch(query)
            }
        })

        // Handle search action
        binding.inlineSearchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.inlineSearchEditText.text?.toString() ?: ""
                performInlineSearch(query)
                true
            } else {
                false
            }
        }
    }

    private fun creditsCodeMatches(input: String): Boolean {
        val configuredHash = BuildConfig.CREDITS_ADMIN_CODE_HASH
        if (configuredHash.isEmpty()) return false

        val inputHash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return MessageDigest.isEqual(
            inputHash.toByteArray(Charsets.UTF_8),
            configuredHash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun showCreditsEditor() {
        val creditsInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(
                getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
                    .getInt("translation_credits", 200)
                    .toString()
            )
            selectAll()
            minHeight = resources.getDimensionPixelSize(com.google.android.material.R.dimen.mtrl_min_touch_target_size)
            setPadding(48, 12, 48, 12)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit translation credits")
            .setMessage("Enter the new number of credits")
            .setView(creditsInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ ->
                val credits = creditsInput.text.toString().toIntOrNull()
                if (credits == null || credits < 0) {
                    Toast.makeText(this, "Enter a valid credit amount", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("translation_credits", credits)
                    .apply()
                updateCreditsDisplay(credits)
                Toast.makeText(this, "Credits updated", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Perform inline search with results display and navigation
     */
    private fun performInlineSearch(query: String) {
        if (query.isEmpty()) {
            binding.webView.clearMatches()
            binding.searchResultsPreview.visibility = View.GONE
            binding.searchResultsCounter.text = "[0/0]"
            binding.searchPreviousButton.isEnabled = false
            binding.searchNextButton.isEnabled = false
            currentSearchIndex = 0
            totalSearchResults = 0
            return
        }

        // Trailing-space logic: exact word match when query ends with a space
        val hasTrailingSpace = query.endsWith(" ")
        val trimmed = query.trimEnd()
        if (hasTrailingSpace && trimmed.isNotEmpty()) {
            Log.d(TAG, "🔍 Enhanced Search: Exact word mode for '$trimmed'")
            performExactWordSearch(trimmed)
            return
        }

        // Standard partial matching search
        binding.webView.findAllAsync(query)

        // Set up WebView find listener to get result count
        binding.webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (isDoneCounting) {
                totalSearchResults = numberOfMatches
                currentSearchIndex = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0

                // Update counter display
                binding.searchResultsCounter.text = "[$currentSearchIndex/$totalSearchResults]"

                // Enable/disable navigation buttons
                binding.searchPreviousButton.isEnabled = numberOfMatches > 1
                binding.searchNextButton.isEnabled = numberOfMatches > 1


                // Show search results preview if query is substantial
                if (query.length >= 3 && numberOfMatches > 0) {
                    showInlineSearchResults(query, numberOfMatches)
                } else {
                    binding.searchResultsPreview.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Navigate through search results
     */
    private fun navigateSearchResult(direction: Int) {
        if (totalSearchResults <= 0) return

        if (direction > 0) {
            // Next result
            binding.webView.findNext(true)
        } else {
            // Previous result
            binding.webView.findNext(false)
        }
    }
    /**
     * Exact word search using JavaScript with word boundaries (\b)
     */
    private fun performExactWordSearch(searchQuery: String) {
        val js = """
            (function() {
                try {
                    // Remove previous highlights
                    var prev = document.querySelectorAll('.search-highlight');
                    prev.forEach(function(span){
                        var t = document.createTextNode(span.textContent);
                        var p = span.parentNode;
                        p.replaceChild(t, span);
                        p.normalize();
                    });

                    // Build regex with word boundaries
                    const term = ${org.json.JSONObject.quote(searchQuery)};
                    const escaped = term.replace(/[.*+?^${'$'}()|[\]\\]/g, '\\$&');
                    const re = new RegExp("\\\\b" + escaped + "\\\\b", "gi");

                    // Walk text nodes and wrap matches
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    const nodes = [];
                    let n; while ((n = walker.nextNode())) { if (n.nodeValue.trim().length > 0) nodes.push(n); }

                    let count = 0;
                    nodes.forEach(function(node){
                        const t = node.nodeValue;
                        if (re.test(t)) {
                            const html = t.replace(re, function(m){ count++; return '<span class="search-highlight" style="background-color:#ffeb3b;color:#000;">' + m + '</span>'; });
                            const div = document.createElement('div');
                            div.innerHTML = html;
                            const frag = document.createDocumentFragment();
                            while (div.firstChild) { frag.appendChild(div.firstChild); }
                            node.parentNode.replaceChild(frag, node);
                        }
                    });

                    // Scroll first into view
                    const first = document.querySelector('.search-highlight');
                    if (first) first.scrollIntoView({behavior:'smooth', block:'center'});

                    return count;
                } catch (e) { return -1; }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js) { raw ->
            val cleaned = raw?.trim()?.trim('"')
            val matches = cleaned?.toIntOrNull() ?: 0
            totalSearchResults = if (matches >= 0) matches else 0
            currentSearchIndex = if (totalSearchResults > 0) 1 else 0
            binding.searchResultsCounter.text = "[$currentSearchIndex/$totalSearchResults]"
            binding.searchPreviousButton.isEnabled = totalSearchResults > 1
            binding.searchNextButton.isEnabled = totalSearchResults > 1
            if (totalSearchResults > 0) {
                showInlineSearchResults(searchQuery, totalSearchResults)
            } else {
                binding.searchResultsPreview.visibility = View.GONE
            }
            Log.d(TAG, "\uD83D\uDD0D Exact word search done: matches=$matches for '$searchQuery'")
        }
    }


    /**
     * Show inline search results preview
     */
    private fun showInlineSearchResults(query: String, resultCount: Int) {
        // Search navigation already uses the WebView match count and controls.
        // Keep the unused placeholder preview collapsed so it cannot reserve space.
        binding.searchResultsPreview.visibility = View.GONE
        Log.d(TAG, "Search preview disabled; found $resultCount matches for '$query'")
    }

    data class SearchResultItem(
        val text: String,
        val location: String,
        val resultIndex: Int
    )

    /**
     * Show search results in RecyclerView (legacy method - no longer used)
     */
    private fun showSearchResults(query: String) {
        // This method is no longer used with the new inline search interface
        // Search functionality is now handled by performInlineSearch()
        Log.d(TAG, "Legacy showSearchResults called for query: $query")
    }





    /**
     * Extract chapter title from HTML content
     */
    private fun extractChapterTitle(htmlContent: String, pageIndex: Int, fileName: String = "unknown"): String {
        try {
            // Prefer official TOC title if available
            if (fileName != "unknown") {
                tocTitleMap[fileName]?.let { tocTitle ->
                    val title = tocTitle.trim()
                    if (title.isNotEmpty()) {
                        Log.d(TAG, "📖 Chapter Title: Using TOC title for page ${'$'}{pageIndex + 1}: '${'$'}title' (from ${'$'}fileName)")
                        return formatChapterTitle(title, pageIndex + 1)
                    }
                }
            }

            // Parse HTML content using JSoup
            val doc = org.jsoup.Jsoup.parse(htmlContent)

            // Try multiple strategies to find a meaningful title

            // Strategy 1: Look for title tag
            val titleElement = doc.select("title").first()
            if (titleElement != null) {
                val title = titleElement.text().trim()
                if (title.isNotEmpty() && !isGenericTitle(title)) {
                    Log.d(TAG, "📖 Chapter Title: Found <title> for page ${pageIndex + 1}: '$title'")
                    return formatChapterTitle(title, pageIndex + 1)
                }
            }

            // Strategy 2: Look for main heading tags (h1, h2, h3)
            val headings = doc.select("h1, h2, h3")
            for (heading in headings) {
                val headingText = heading.text().trim()
                if (headingText.isNotEmpty() && !isGenericTitle(headingText)) {
                    Log.d(TAG, "📖 Chapter Title: Found <${heading.tagName()}> for page ${pageIndex + 1}: '$headingText'")
                    return formatChapterTitle(headingText, pageIndex + 1)
                }
            }

            // Strategy 3: Look for elements with specific classes that might contain chapter titles
            val chapterElements = doc.select(".chapter, .chapter-title, .title, .heading, [class*=chapter], [class*=title]")
            for (element in chapterElements) {
                val text = element.text().trim()
                if (text.isNotEmpty() && !isGenericTitle(text)) {
                    Log.d(TAG, "📖 Chapter Title: Found chapter class for page ${pageIndex + 1}: '$text'")
                    return formatChapterTitle(text, pageIndex + 1)
                }
            }

            // Strategy 4: Look for the first significant paragraph or div that might be a title
            val firstElements = doc.select("p, div").take(3) // Check first 3 elements
            for (element in firstElements) {
                val text = element.text().trim()
                if (text.isNotEmpty() && text.length < 100 && !isGenericTitle(text)) {
                    // Check if it looks like a title (short, possibly capitalized)
                    if (looksLikeTitle(text)) {
                        Log.d(TAG, "📖 Chapter Title: Found title-like text for page ${pageIndex + 1}: '$text'")
                        return formatChapterTitle(text, pageIndex + 1)
                    }
                }
            }

            // Strategy 5: Extract from filename if available
            if (fileName != "unknown") {
                val fileBaseName = fileName.substringAfterLast("/").substringBeforeLast(".")
                if (fileBaseName.isNotEmpty() && !isGenericTitle(fileBaseName)) {
                    // Clean up filename for display
                    val cleanFileName = fileBaseName
                        .replace("_", " ")
                        .replace("-", " ")
                        .split(" ")
                        .joinToString(" ") { word ->
                            word.lowercase().replaceFirstChar { it.uppercase() }
                        }

                    if (!isGenericTitle(cleanFileName)) {
                        Log.d(TAG, "📖 Chapter Title: Found filename-based title for page ${pageIndex + 1}: '$cleanFileName' (from $fileName)")
                        return formatChapterTitle(cleanFileName, pageIndex + 1)
                    }
                }
            }

            Log.d(TAG, "📖 Chapter Title: No meaningful title found for page ${pageIndex + 1} (file: $fileName), using default")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting chapter title for page ${pageIndex + 1}", e)
        }

        // Fallback: Use page number
        return "Page ${pageIndex + 1}"
    }

    /**
     * Check if a title is generic and should be ignored
     */
    private fun isGenericTitle(title: String): Boolean {
        val lowerTitle = title.lowercase().trim()
        val genericTitles = listOf(
            "untitled", "document", "page", "chapter", "section",
            "content", "text", "body", "main", "default",
            "epub", "book", "reader", "html", "xhtml"
        )

        // Check if title is just a generic word
        if (genericTitles.any { lowerTitle == it || lowerTitle.contains(it) && lowerTitle.length < 20 }) {
            return true
        }

        // Check if title is just numbers or very short
        if (lowerTitle.length < 3 || lowerTitle.matches("\\d+".toRegex())) {
            return true
        }

        return false
    }

    /**
     * Check if text looks like a title based on formatting patterns
     */
    private fun looksLikeTitle(text: String): Boolean {
        // Title characteristics:
        // - Relatively short (less than 100 characters)
        // - Might be all caps or title case
        // - Doesn't end with a period (usually)
        // - Contains meaningful words

        if (text.length > 100) return false

        // Check if it's mostly uppercase (might be a title)
        val uppercaseRatio = text.count { it.isUpperCase() }.toFloat() / text.count { it.isLetter() }
        if (uppercaseRatio > 0.7) return true

        // Check if it starts with capital and doesn't end with period
        if (text.first().isUpperCase() && !text.endsWith(".")) {
            // Check if it contains chapter-like words
            val chapterWords = listOf("chapter", "part", "section", "prologue", "epilogue", "introduction", "conclusion")
            if (chapterWords.any { text.lowercase().contains(it) }) {
                return true
            }
        }

        return false
    }

    /**
     * Format chapter title for display in TOC
     */
    private fun formatChapterTitle(title: String, pageNumber: Int): String {
        var formattedTitle = title.trim()

        // Limit title length for better display
        if (formattedTitle.length > 50) {
            formattedTitle = formattedTitle.take(47) + "..."
        }

        // Add page number prefix for clarity
        return "[$pageNumber] $formattedTitle"
    }

    /**
     * Validate TOC consistency and report any issues
     */
    private fun validateTocConsistency(): String {
        val issues = mutableListOf<String>()

        // Check basic counts
        if (totalPages != htmlFiles.size) {
            issues.add("Page count mismatch: totalPages=$totalPages, htmlFiles.size=${htmlFiles.size}")
        }

        // Check for empty HTML files
        var emptyFiles = 0
        htmlFiles.forEachIndexed { index, content ->
            if (content.trim().isEmpty()) {
                emptyFiles++
                issues.add("Empty HTML content at page ${index + 1}")
            }
        }

        if (emptyFiles > 0) {
            issues.add("Found $emptyFiles empty HTML files")
        }

        // Check for very short files that might be missing content
        var shortFiles = 0
        htmlFiles.forEachIndexed { index, content ->
            if (content.trim().length < 50) {
                shortFiles++
                issues.add("Very short HTML content at page ${index + 1} (${content.trim().length} chars)")
            }
        }

        if (shortFiles > 0) {
            issues.add("Found $shortFiles very short HTML files")
        }

        return if (issues.isEmpty()) {
            "✅ TOC validation passed - no issues found"
        } else {
            "⚠️ TOC validation found ${issues.size} issues:\n" + issues.joinToString("\n")
        }
    }

    /**
     * Validate that TOC order matches the original EPUB spine order
     */
    private fun validateSpineOrder(): String {
        val issues = mutableListOf<String>()

        Log.d(TAG, "📚 SPINE VALIDATION: Starting spine order validation")

        // Check if we have spine order information
        if (htmlFileNames.isEmpty()) {
            issues.add("No spine order information available")
            return "⚠️ Spine validation: No spine order data to validate"
        }

        // Check if spine order matches content order
        if (htmlFileNames.size != htmlFiles.size) {
            issues.add("Spine file count (${htmlFileNames.size}) != content count (${htmlFiles.size})")
        }

        // Log the current order for verification
        Log.d(TAG, "📚 SPINE VALIDATION: Current TOC order:")
        htmlFileNames.forEachIndexed { index, fileName ->
            Log.d(TAG, "📚 SPINE VALIDATION: [$index] $fileName")
        }

        // Check for any obvious ordering issues
        val chapterNumbers = mutableListOf<Int>()
        htmlFileNames.forEach { fileName ->
            // Try to extract chapter numbers from filenames
            val numberMatch = "chapter[_-]?(\\d+)|ch[_-]?(\\d+)|(\\d+)[_-]?chapter".toRegex(RegexOption.IGNORE_CASE)
                .find(fileName)
            if (numberMatch != null) {
                val number = numberMatch.groupValues.find { it.matches("\\d+".toRegex()) }?.toIntOrNull()
                if (number != null) {
                    chapterNumbers.add(number)
                }
            }
        }

        // Check if chapter numbers are in sequence
        if (chapterNumbers.size > 1) {
            val sortedNumbers = chapterNumbers.sorted()
            if (chapterNumbers != sortedNumbers) {
                issues.add("Chapter numbers appear out of sequence: ${chapterNumbers.joinToString(", ")}")
                Log.w(TAG, "📚 SPINE VALIDATION: ⚠️ Chapter sequence issue detected")
                Log.w(TAG, "📚 SPINE VALIDATION: Current order: ${chapterNumbers.joinToString(", ")}")
                Log.w(TAG, "📚 SPINE VALIDATION: Expected order: ${sortedNumbers.joinToString(", ")}")
            } else {
                Log.d(TAG, "📚 SPINE VALIDATION: ✅ Chapter numbers in correct sequence")
            }
        }

        return if (issues.isEmpty()) {
            "✅ Spine validation passed - chapter order appears correct"
        } else {
            "⚠️ Spine validation found ${issues.size} potential issues:\n" + issues.joinToString("\n")
        }
    }

    // Track whether spine ordering was successfully used for this EPUB
    private var spineOrderWasUsed = false

    /**
     * Check if spine order was successfully used for this EPUB loading
     */
    private fun checkIfSpineOrderWasUsed(): Boolean {
        return spineOrderWasUsed
    }

    /**
     * Check if spine order was used before for this book (stored in preferences)
     */
    private fun wasSpineOrderUsedBefore(filePath: String): Boolean {
        val key = "spine_order_used_${File(filePath).name}_${filePath.hashCode()}"
        return getSharedPreferences("epub_spine_prefs", Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    /**
     * Mark that spine order was used for this book
     */
    private fun markSpineOrderAsUsed(filePath: String) {
        val key = "spine_order_used_${File(filePath).name}_${filePath.hashCode()}"
        getSharedPreferences("epub_spine_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .apply()
    }

    /**
     * Parse the EPUB spine to get the correct reading order
     */
    private fun parseEpubSpine(zipFile: ZipFile, allEntries: List<ZipEntry>): List<String> {
        try {
            // First, find the container.xml file
            val containerEntry = allEntries.find { it.name == "META-INF/container.xml" }
            if (containerEntry == null) {
                Log.w(TAG, "container.xml not found, cannot determine reading order")
                return emptyList()
            }

            // Parse container.xml to find the OPF file location
            val containerContent = readZipEntry(zipFile, containerEntry)
            val opfPath = extractOpfPath(containerContent)
            if (opfPath == null) {
                Log.w(TAG, "OPF path not found in container.xml")
                return emptyList()
            }

            Log.d(TAG, "Found OPF file at: $opfPath")

            // Find and parse the OPF file
            val opfEntry = allEntries.find { it.name == opfPath }
            if (opfEntry == null) {
                Log.w(TAG, "OPF file not found: $opfPath")
                return emptyList()
            }

            val opfContent = readZipEntry(zipFile, opfEntry)
            // Parse spine order
            val spine = parseSpineFromOpf(opfContent, opfPath)
            // Parse TOC titles (nav.xhtml or toc.ncx) and build a map from href->title
            try {
                tocTitleMap.clear()
                tocTargetMap.clear()
                tocTitleMap.putAll(parseTocTitleMapFromOpf(opfContent, allEntries, zipFile, opfPath))
                Log.d(TAG, "Parsed ${'$'}{tocTitleMap.size} TOC titles from EPUB")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse TOC titles", e)
            }
            return spine

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing EPUB spine", e)
            return emptyList()
        }
    }

    /**
     * Extract the OPF file path from container.xml
     */
    private fun extractOpfPath(containerXml: String): String? {
        try {
            // Try multiple patterns to find the OPF file path
            val patterns = listOf(
                // Standard pattern with media-type first
                "<rootfile[^>]+full-path=\"([^\"]+)\"[^>]*media-type=\"application/oebps-package\\+xml\"[^>]*>".toRegex(),
                // Pattern with media-type after full-path
                "<rootfile[^>]+media-type=\"application/oebps-package\\+xml\"[^>]*full-path=\"([^\"]+)\"[^>]*>".toRegex(),
                // More flexible pattern
                "<rootfile[^>]*full-path=\"([^\"]+)\"[^>]*>".toRegex()
            )

            for (pattern in patterns) {
                val match = pattern.find(containerXml)
                if (match != null) {
                    val path = match.groupValues[1]
                    Log.d(TAG, "Found OPF path using pattern: $path")
                    return path
                }
            }

            Log.w(TAG, "No OPF path found in container.xml")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting OPF path", e)
            return null
        }
    }

    /**
     * Parse the spine from the OPF file content
     */
    private fun parseSpineFromOpf(opfContent: String, opfPath: String): List<String> {
        try {
            val doc = Jsoup.parse(opfContent, "", Parser.xmlParser())
            val manifestMap = doc.select("manifest item").associate { item ->
                item.attr("id") to item.attr("href")
            }
            val spineItems = doc.select("spine itemref").mapNotNull { itemref ->
                val idref = itemref.attr("idref")
                val href = manifestMap[idref]
                if (href.isNullOrBlank()) {
                    Log.w(TAG, "Manifest item not found for spine idref: $idref")
                    null
                } else {
                    val fullPath = normalizeEpubPath(resolveRelativePath(Uri.decode(href), opfPath))
                    Log.d(TAG, "Added spine item: $idref -> $fullPath")
                    fullPath
                }
            }

            Log.d(TAG, "Parsed ${spineItems.size} spine items")
            return spineItems

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing spine from OPF", e)
            return emptyList()
        }
    }

    /**
     * Process HTML content to fix image references
     */
    private fun processHtmlContent(html: String, entryPath: String, imageCache: File, imageEntries: Map<String, String>): String {
        try {
            val doc = Jsoup.parse(html)

            // Fix image sources
            val images = doc.select("img")
            Log.d(TAG, "Processing ${images.size} images in ${entryPath}")

            for (img in images) {
                val src = img.attr("src")
                if (src.isNotEmpty()) {
                    Log.d(TAG, "Processing image src: $src")

                    // Handle relative paths
                    val absolutePath = resolveRelativePath(src, entryPath)
                    Log.d(TAG, "Resolved absolute path: $absolutePath")

                    // Try multiple approaches to find the image with enhanced debugging
                    var imagePath: String? = null
                    val searchPaths = mutableListOf<String>()

                    // Approach 1: Direct match in imageEntries
                    searchPaths.add(absolutePath)
                    imagePath = imageEntries[absolutePath]
                    if (imagePath != null) {
                        Log.d(TAG, "Found image via direct match: $absolutePath -> $imagePath")
                    }

                    // Approach 2: Try original src path
                    if (imagePath == null) {
                        searchPaths.add(src)
                        imagePath = imageEntries[src]
                        if (imagePath != null) {
                            Log.d(TAG, "Found image via original src: $src -> $imagePath")
                        }
                    }

                    // Approach 3: Try without leading slash
                    if (imagePath == null && absolutePath.startsWith("/")) {
                        val pathWithoutSlash = absolutePath.substring(1)
                        searchPaths.add(pathWithoutSlash)
                        imagePath = imageEntries[pathWithoutSlash]
                        if (imagePath != null) {
                            Log.d(TAG, "Found image without leading slash: $pathWithoutSlash -> $imagePath")
                        }
                    }

                    // Approach 4: Try with leading slash
                    if (imagePath == null && !absolutePath.startsWith("/")) {
                        val pathWithSlash = "/$absolutePath"
                        searchPaths.add(pathWithSlash)
                        imagePath = imageEntries[pathWithSlash]
                        if (imagePath != null) {
                            Log.d(TAG, "Found image with leading slash: $pathWithSlash -> $imagePath")
                        }
                    }

                    // Approach 5: Try case-insensitive match
                    if (imagePath == null) {
                        val lowerPath = absolutePath.lowercase()
                        val matchedEntry = imageEntries.entries.find {
                            it.key.lowercase() == lowerPath
                        }
                        imagePath = matchedEntry?.value
                        if (imagePath != null) {
                            Log.d(TAG, "Found image via case-insensitive match: ${matchedEntry?.key} -> $imagePath")
                        }
                    }

                    // Approach 6: Try filename-only match (for images in different directories)
                    if (imagePath == null) {
                        val filename = absolutePath.substringAfterLast("/")
                        if (filename.isNotEmpty()) {
                            val matchedEntry = imageEntries.entries.find {
                                it.key.substringAfterLast("/") == filename
                            }
                            imagePath = matchedEntry?.value
                            if (imagePath != null) {
                                Log.d(TAG, "Found image via filename match: $filename -> $imagePath")
                            }

                        }
                    }

                    // Log all attempted paths if image not found
                    if (imagePath == null) {
                        Log.w(TAG, "Image not found after trying paths: ${searchPaths.joinToString(", ")}")
                        Log.w(TAG, "Available image entries: ${imageEntries.keys.take(10).joinToString(", ")}${if (imageEntries.size > 10) "..." else ""}")
                    }

                    if (imagePath != null) {
                        // Use file:// protocol for local files
                        img.attr("src", "file://$imagePath")
                        Log.d(TAG, "Set image src to: file://$imagePath")

                        // Add loading attributes for better image handling
                        img.attr("loading", "lazy")
                        img.attr("decoding", "async")
                    } else {
                        // Try with the safe filename approach as fallback
                        val safeFileName = absolutePath.replace("/", "_").replace("\\", "_")
                        val imageFile = File(imageCache, safeFileName)

                        if (imageFile.exists()) {
                            // Use file:// protocol for local files
                            img.attr("src", "file://${imageFile.absolutePath}")
                            Log.d(TAG, "Set image src to cached file: file://${imageFile.absolutePath}")

                            // Add loading attributes for better image handling
                            img.attr("loading", "lazy")
                            img.attr("decoding", "async")
                        } else {
                            Log.w(TAG, "Image not found: $absolutePath (safe filename: $safeFileName)")
                        }
                    }
                }
            }

            return doc.outerHtml()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing HTML content", e)
            return html
        }
    }

    /**
     * Enhanced resolve a relative path against a base path with better debugging
     */
    private fun resolveRelativePath(relativePath: String, basePath: String): String {
        Log.d(TAG, "Resolving relative path: '$relativePath' against base: '$basePath'")

        // If the path is already absolute or contains protocol, return it
        if (relativePath.startsWith("/") || relativePath.contains("://") || relativePath.startsWith("data:")) {
            Log.d(TAG, "Path is already absolute: $relativePath")
            return relativePath
        }

        // Get the directory part of the base path
        val baseDir = basePath.substringBeforeLast("/", "")
        Log.d(TAG, "Base directory: '$baseDir'")

        // Handle parent directory references (../)
        var path = relativePath
        var dir = baseDir

        while (path.startsWith("../")) {
            path = path.substring(3)
            val oldDir = dir
            dir = dir.substringBeforeLast("/", "")
            Log.d(TAG, "Moving up directory: '$oldDir' -> '$dir', remaining path: '$path'")
        }

        // Handle current directory references (./)
        if (path.startsWith("./")) {
            path = path.substring(2)
            Log.d(TAG, "Removed current directory reference, path: '$path'")
        }

        // Combine the directory and the relative path
        val resolvedPath = if (dir.isEmpty()) path else "$dir/$path"
        Log.d(TAG, "Final resolved path: '$resolvedPath'")

        return resolvedPath
    }


    /**
     * Parse TOC titles from nav.xhtml (EPUB3) or toc.ncx (EPUB2) and return a map of spine href -> title
     */
    private fun parseTocTitleMapFromOpf(
        opfContent: String,
        allEntries: List<ZipEntry>,
        zipFile: ZipFile,
        opfPath: String
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        try {
            val opfDoc = Jsoup.parse(opfContent, "", Parser.xmlParser())
            val navItem = opfDoc.select("manifest item").firstOrNull {
                it.attr("properties").split(Regex("\\s+")).any { property -> property.equals("nav", true) }
            }
            if (navItem != null) {
                val navPath = normalizeEpubPath(resolveRelativePath(Uri.decode(navItem.attr("href")), opfPath))
                val navEntry = allEntries.firstOrNull { normalizeEpubPath(it.name) == navPath }
                if (navEntry != null) {
                    val navDoc = Jsoup.parse(readZipEntry(zipFile, navEntry))
                    val tocNav = navDoc.select("nav").firstOrNull { nav ->
                        nav.attr("epub:type").split(Regex("\\s+")).any { it.equals("toc", true) } ||
                            nav.attr("type").equals("toc", true) || nav.id().equals("toc", true) || nav.classNames().any { it.equals("toc", true) }
                    }
                    tocNav?.select("a[href]")?.forEach { link ->
                        val href = link.attr("href")
                        val base = href.substringBefore("#")
                        val normalized = normalizeEpubPath(resolveRelativePath(Uri.decode(base), navPath))
                        val text = link.text().trim()
                        if (text.isNotEmpty()) {
                            result[normalized] = text
                            tocTargetMap[normalized] = href
                        }
                    }
                }
            }
            if (result.isNotEmpty()) return result

            val spineTocId = opfDoc.selectFirst("spine")?.attr("toc").orEmpty()
            val ncxHref = opfDoc.select("manifest item").firstOrNull { it.attr("id") == spineTocId }?.attr("href")
            if (!ncxHref.isNullOrBlank()) {
                val ncxPath = normalizeEpubPath(resolveRelativePath(Uri.decode(ncxHref), opfPath))
                val ncxEntry = allEntries.firstOrNull { normalizeEpubPath(it.name) == ncxPath }
                if (ncxEntry != null) {
                    val ncxDoc = Jsoup.parse(readZipEntry(zipFile, ncxEntry), "", Parser.xmlParser())
                    ncxDoc.select("navPoint").forEach { navPoint ->
                        val label = navPoint.selectFirst("navLabel text")?.text()?.trim().orEmpty()
                        val href = navPoint.selectFirst("content")?.attr("src").orEmpty()
                        if (label.isNotEmpty() && href.isNotEmpty()) {
                            val base = href.substringBefore("#")
                            val normalized = normalizeEpubPath(resolveRelativePath(Uri.decode(base), ncxPath))
                            result[normalized] = label
                            tocTargetMap[normalized] = href
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TOC title parsing error", e)
        }
        return result
    }


    private fun loadPage(pageIndex: Int, scrollPositionOverride: Int? = null) {
        if (pageIndex < 0 || pageIndex >= htmlFiles.size) {
            return
        }

        if (pageIndex != currentPage && !restoringNavigationHistory && htmlFiles.isNotEmpty()) {
            navigationHistory.addLast(ReaderLocation(currentPage, binding.webView.scrollY))
        }
        restoringNavigationHistory = false
        pendingScrollPosition = scrollPositionOverride

        val htmlContent = htmlFiles[pageIndex]

        // Clean the HTML content using JSoup
        val cleanHtml = cleanHtml(htmlContent)

        // Show loading indicator for better user experience
        binding.progressBar.visibility = View.VISIBLE

        // Apply a fade-out effect to reduce the perception of glitches
        binding.webView.alpha = 0.3f

        // Get the target scroll position before loading the page
        val targetScrollY = scrollPositionOverride ?: pageScrollPositions[pageIndex] ?: 0
        Log.d(TAG, "Target scroll position for page $pageIndex: $targetScrollY")

        // Load the HTML content into the WebView
        binding.webView.loadDataWithBaseURL(null, cleanHtml, "text/html", "UTF-8", null)

        // Update page number and navigation controls
        currentPage = pageIndex
        updateNavigationControls()

        // The scroll position will be restored in onPageFinished, but we'll also set up a backup approach
        // with multiple attempts to ensure it works reliably
        if (targetScrollY > 0) {
            // First immediate attempt
            binding.webView.postDelayed({
                binding.webView.scrollTo(0, targetScrollY)
            }, 50)

            // Second attempt after a short delay
            binding.webView.postDelayed({
                binding.webView.scrollTo(0, targetScrollY)
                // Hide loading indicator
                binding.progressBar.visibility = View.GONE
            }, 200)



            // Final attempt after content should definitely be loaded
            binding.webView.postDelayed({
                if (binding.webView.scrollY != targetScrollY) {
                    binding.webView.scrollTo(0, targetScrollY)
                }
                // Ensure loading indicator is hidden
                binding.progressBar.visibility = View.GONE
            }, 500)
        } else {
            // If no scroll position, just hide the loading indicator and fade in after a short delay
            binding.webView.postDelayed({
                binding.progressBar.visibility = View.GONE
                binding.webView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .start()
            }, 100)
        }
    }

    private fun handleBookLink(url: String): Boolean {
        val normalizedUrl = if (url.startsWith("//")) "https:$url" else url
        val uri = runCatching { Uri.parse(normalizedUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "No external app available for link: $normalizedUrl", e)
                false
            }
        }

        val fragment = uri.fragment?.let { Uri.decode(it) }
        val rawPath = uri.path?.let { Uri.decode(it) }?.replace('\\', '/')?.trimStart('/') ?: ""
        val currentFile = htmlFileNames.getOrNull(currentPage)?.replace('\\', '/') ?: ""
        val path = if (rawPath.isEmpty()) {
            ""
        } else if (rawPath.startsWith("/")) {
            normalizeEpubPath(rawPath)
        } else {
            normalizeEpubPath(resolveRelativePath(rawPath, currentFile))
        }
        val targetIndex = if (path.isEmpty()) {
            currentPage
        } else {
            htmlFileNames.indexOfFirst {
                val candidate = normalizeEpubPath(it)
                candidate == path
            }
        }
        if (targetIndex < 0) return false

        if (targetIndex != currentPage) {
            pendingAnchor = fragment
            loadPage(targetIndex)
        } else if (!fragment.isNullOrEmpty()) {
            binding.webView.evaluateJavascript(
                "(document.getElementById(${JSONObject.quote(fragment)}) || document.getElementsByName(${JSONObject.quote(fragment)})[0])?.scrollIntoView({block:'start'});",
                null
            )
        }
        return true
    }

    private fun normalizeEpubPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun cleanHtml(html: String): String {
        try {
            val doc: Document = Jsoup.parse(html)

            // Add viewport meta tag for responsive design
            val head = doc.head()
            if (head.select("meta[name=viewport]").isEmpty()) {
                head.appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1.0")
            }

            // Add some basic styling
            head.appendElement("style").text("""
                body { font-family: Arial, sans-serif; line-height: 1.6; padding: 20px; }

                /* Image styling */
                img {
                    max-width: 100%;
                    height: auto;
                    display: block;
                    margin: 10px auto;
                    object-fit: contain;
                    border-radius: 4px;
                }

                /* Figure styling */
                figure {
                    text-align: center;
                    margin: 15px 0;
                    max-width: 100%;
                    overflow: hidden;
                }
                figcaption {
                    font-style: italic;
                    font-size: 0.9em;
                    margin-top: 5px;
                }

                /* Text element styling */
                p, div, span, h1, h2, h3, h4, h5, h6, li {
                    cursor: pointer;
                    -webkit-user-select: text;
                    user-select: text;
                }
                /* Hover/active effects removed to avoid gray backgrounds */

                /* Ensure backgrounds reset when not hovering/active */
                p, div, span, h1, h2, h3, h4, h5, h6, li {
                    background-color: transparent !important;
                    transition: background-color 0.1s ease-out;
                }

                /* Container styling */
                .translatable {
                    border: 1px dashed transparent;
                }
                .translatable:hover {
                    border-color: rgba(0, 0, 255, 0.2);
                }
                .image-container {
                    text-align: center;
                    margin: 15px 0;
                    max-width: 100%;
                }

                /* Fix for SVG images */
                svg {
                    max-width: 100%;
                    height: auto;
                }
            """)

            return doc.outerHtml()
        } catch (e: Exception) {
            e.printStackTrace()

            // Fallback to basic HTML wrapping if JSoup fails
            return """<!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { font-family: Arial, sans-serif; line-height: 1.6; padding: 20px; }

                        /* Image styling */
                        img {
                            max-width: 100%;
                            height: auto;
                            display: block;
                            margin: 10px auto;
                            object-fit: contain;
                            border-radius: 4px;
                        }

                        /* Figure styling */
                        figure {
                            text-align: center;
                            margin: 15px 0;
                            max-width: 100%;
                            overflow: hidden;
                        }
                        figcaption {
                            font-style: italic;
                            font-size: 0.9em;
                            margin-top: 5px;
                        }

                        /* Text element styling */
                        p, div, span, h1, h2, h3, h4, h5, h6, li {
                            cursor: pointer;
                            -webkit-user-select: text;
                            user-select: text;
                        }
                        /* Hover/active effects removed to avoid gray backgrounds */

                        /* Ensure backgrounds reset when not hovering/active */
                        p, div, span, h1, h2, h3, h4, h5, h6, li {
                            background-color: transparent !important;
                            transition: background-color 0.1s ease-out;
                        }

                        /* Container styling */
                        .translatable {
                            border: 1px dashed transparent;
                        }
                        .translatable:hover {
                            border-color: rgba(0, 0, 255, 0.2);
                        }
                        .image-container {
                            text-align: center;
                            margin: 15px 0;
                            max-width: 100%;
                        }

                        /* Fix for SVG images */
                        svg {
                            max-width: 100%;
                            height: auto;
                        }
                    </style>
                </head>
                <body>
                    $html
                </body>
                </html>"""
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        // Configure WebView settings
        binding.webView.settings.apply {
            // Enable JavaScript
            javaScriptEnabled = true

            // Disable DOM storage to prevent caching
            domStorageEnabled = false

            // Enable loading images
            loadsImagesAutomatically = true

            // Enable file access for loading local images
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true

            // Enable mixed content (http content in https pages)
            @Suppress("DEPRECATION")
            setSupportMultipleWindows(true)

            // Enable zooming
            builtInZoomControls = true
            displayZoomControls = false

            // Use wide viewport for better rendering
            useWideViewPort = true
            loadWithOverviewMode = true

            // Disable all caching
            @Suppress("DEPRECATION")
            setCacheMode(WebSettings.LOAD_NO_CACHE)

            // Disable database storage
            databaseEnabled = false

            // Set default text size
            textZoom = 100
        }

        // Clear any existing WebView cache
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.webView.clearFormData()

        // Add JavaScript interface
        binding.webView.addJavascriptInterface(JavaScriptInterface(), JS_INTERFACE_NAME)

        // Set WebChromeClient to capture JavaScript console messages
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d(TAG, "JS Console [${it.messageLevel()}]: ${it.message()} at ${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }
        }

        // Set WebViewClient to inject our JavaScript after page load
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                return url.isNotEmpty() && handleBookLink(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return !url.isNullOrEmpty() && handleBookLink(url)
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error: ${error?.description} for URL: ${request?.url}")
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                Log.e(TAG, "WebView HTTP error: ${errorResponse?.statusCode} for URL: ${request?.url}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Hide the progress bar
                binding.progressBar.visibility = View.GONE

                // Apply a smooth fade-in effect
                binding.webView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .start()

                // A TOC jump explicitly overrides the saved reading position.
                val forcedScrollPosition = pendingScrollPosition
                pendingScrollPosition = null

                // Get the target scroll position
                val targetScrollY = forcedScrollPosition ?: pageScrollPositions[currentPage] ?: run {
                    // If not in memory, try to get from page-specific persistent storage
                    epubFilePath?.let { path ->
                        val pageSpecificScrollY = bookPositionManager.getScrollPosition("${path}_page_$currentPage")
                        if (pageSpecificScrollY > 0) {
                            // Store in memory for future use
                            pageScrollPositions[currentPage] = pageSpecificScrollY
                            return@run pageSpecificScrollY
                        }

                        // Fall back to the global saved position as last resort
                        val savedScrollY = bookPositionManager.getScrollPosition(path)
                        if (savedScrollY > 0 && currentPage == bookPositionManager.getPosition(path)) {
                            return@run savedScrollY
                        }
                    }
                    0 // Default value if no saved position
                }

                // Apply the scroll position with a more robust approach
                if (targetScrollY > 0) {
                    Log.d(TAG, "onPageFinished: Applying scroll position $targetScrollY for page $currentPage")

                    // Use a sequence of delayed attempts to ensure the scroll position is applied
                    // First immediate attempt
                    binding.webView.scrollTo(0, targetScrollY)

                    // Second attempt with post to ensure UI thread
                    binding.webView.post {
                        binding.webView.scrollTo(0, targetScrollY)

                        // Third attempt with a delay
                        binding.webView.postDelayed({
                            if (binding.webView.scrollY != targetScrollY) {
                                Log.d(TAG, "Re-applying scroll position after delay")
                                binding.webView.scrollTo(0, targetScrollY)

                                // Final attempt with a longer delay
                                binding.webView.postDelayed({
                                    if (binding.webView.scrollY != targetScrollY) {
                                        Log.d(TAG, "Final attempt to apply scroll position")
                                        binding.webView.scrollTo(0, targetScrollY)
                                    }
                                }, 200)
                            }
                        }, 100)
                    }
                } else if (forcedScrollPosition != null) {
                    binding.webView.post {
                        binding.webView.scrollTo(0, 0)
                    }
                }

                // Always inject JavaScript and reapply theme for the new page
                injectJavaScriptHandlers()
                pendingAnchor?.let { anchor ->
                    pendingAnchor = null
                    binding.webView.postDelayed({
                        val escapedAnchor = JSONObject.quote(anchor)
                        binding.webView.evaluateJavascript(
                            "(document.getElementById($escapedAnchor) || document.getElementsByName($escapedAnchor)[0])?.scrollIntoView({block:'start'});",
                            null
                        )
                    }, 150)
                }
                // Ensure theme preference is consistently applied on every page
                applyTheme()

                // Check if we need to reapply translations
                val hasTranslations = pageTranslations.containsKey(currentPage) && pageTranslations[currentPage]?.isNotEmpty() == true
                val hasVisibleTranslations = pageVisibleTranslations.containsKey(currentPage) && pageVisibleTranslations[currentPage]?.isNotEmpty() == true

                Log.d(TAG, "Translation check for page $currentPage: needToReapply=$needToReapplyTranslations, hasTranslations=$hasTranslations, hasVisibleTranslations=$hasVisibleTranslations")

                if (needToReapplyTranslations || hasTranslations || hasVisibleTranslations) {
                    Log.d(TAG, "Applying stored translations for page $currentPage")
                    // Apply translations after JavaScript is injected and theme is applied
                    binding.webView.postDelayed({
                        applyStoredTranslations()
                    }, 300) // Delay to ensure JavaScript is fully loaded
                } else {
                    Log.d(TAG, "No translations to apply for page $currentPage")
                }

                // Update page scroll progress indicator after load
                binding.webView.post { updateScrollProgress() }


            }
        }

        // Apply initial reading preferences
        applyFontSize()
    }

    /**
     * Inject JavaScript handlers for translation and interaction
     */
    private fun injectJavaScriptHandlers() {
        val jsCode = """
                    // Track clicks and text selection
                    var lastClickTime = 0;
                    var lastClickElement = null;
                    var clickCount = 0;
                    var translatedElements = {};
                    var originalContents = {};
                    var originalStyles = {};
                    var selectionTimer = null;
                    var highlightedElements = {};
                    var currentSelection = null;

                    // Flag to prevent duplicate event processing
                    window.processingTap = false;

                    // Enhanced global touch tracking for swipe detection
                    var globalTouchStartX = 0;
                    var globalTouchStartY = 0;
                    var globalTouchStartTime = 0;
                    var globalHasMoved = false;
                    var MOVEMENT_THRESHOLD = 30; // Increased threshold for better swipe detection
                    var VELOCITY_THRESHOLD = 300; // pixels per second
                    var TAP_TIMEOUT = 250; // milliseconds

                    // Function to mark an element as translated and update the UI
                    window.markElementAsTranslated = function(elementId, isTranslated) {
                        console.log('Marking element ' + elementId + ' as translated: ' + isTranslated);
                        var element = document.querySelector('[data-translator-id="' + elementId + '"]');

                        if (!element) {
                            console.error('Cannot find element with ID: ' + elementId);
                            return false;
                        }

                        if (isTranslated) {
                            // Mark as translated
                            translatedElements[elementId] = true;
                            element.setAttribute('data-translated', 'true');

                            // Add enhanced visual indicator - ensure it applies to entire paragraph as one block
                            element.style.backgroundColor = 'rgba(144, 238, 144, 0.25)';
                            element.style.borderRadius = '4px';
                            element.style.padding = '4px 8px';
                            element.style.display = 'block'; // Force block display to prevent line-by-line highlighting
                            element.style.boxSizing = 'border-box';

                            // Add a special class for easier selection
                            element.classList.add('translator-translated');

                            // Store original content if not already stored
                            if (!originalContents[elementId]) {
                                originalContents[elementId] = element.innerHTML;
                                originalStyles[elementId] = {
                                    direction: element.style.direction || 'ltr',
                                    textAlign: element.style.textAlign || 'left',
                                    borderLeft: element.style.borderLeft || '',
                                    borderRight: element.style.borderRight || '',
                                    paddingLeft: element.style.paddingLeft || '',
                                    paddingRight: element.style.paddingRight || ''
                                };
                            }
                        } else {
                            // Mark as not translated
                            delete translatedElements[elementId];
                            element.removeAttribute('data-translated');
                            element.classList.remove('translator-translated');

                            // Restore original styles
                            if (originalStyles[elementId]) {
                                var styles = originalStyles[elementId];

                                // Get the original text after restoring content
                                var originalText = element.textContent.trim();

                                // IMPROVED APPROACH: Determine the dominant language
                                var hebrewChars = 0;
                                var nonHebrewChars = 0;

                                // Count Hebrew and non-Hebrew characters
                                for (var i = 0; i < originalText.length; i++) {
                                    var charCode = originalText.charCodeAt(i);
                                    // Hebrew Unicode ranges
                                    if ((charCode >= 0x0590 && charCode <= 0x05FF) ||
                                        (charCode >= 0xFB1D && charCode <= 0xFB4F)) {
                                        hebrewChars++;
                                    } else if (charCode > 32) { // Skip whitespace
                                        nonHebrewChars++;
                                    }
                                }

                                console.log('Hebrew chars: ' + hebrewChars + ', Non-Hebrew chars: ' + nonHebrewChars);

                                // Determine dominant language and set direction
                                if (hebrewChars > nonHebrewChars) {
                                    // Hebrew is dominant, set RTL
                                    console.log('Hebrew is dominant, setting RTL');
                                    element.style.direction = 'rtl';
                                    element.style.textAlign = 'right';
                                } else {
                                    // Non-Hebrew is dominant, set LTR
                                    console.log('Non-Hebrew is dominant, setting LTR');
                                    element.style.direction = 'ltr';
                                    element.style.textAlign = 'left';
                                }

                                // Log the applied styles for debugging
                                console.log('Applied direction: ' + element.style.direction);
                                console.log('Applied text-align: ' + element.style.textAlign);

                                // Restore other styles
                                element.style.borderLeft = styles.borderLeft;
                                element.style.borderRight = styles.borderRight;
                                element.style.paddingLeft = styles.paddingLeft;
                                element.style.paddingRight = styles.paddingRight;
                            }

                            // Remove visual indicator
                            element.style.backgroundColor = '';
                            element.style.border = '';
                            element.style.borderRadius = '';
                            element.style.padding = '';
                        }

                        return true;
                    };

                    // Function to toggle translation state for an element
                    window.toggleTranslation = function(elementId) {
                        console.log('Toggling translation for element: ' + elementId);
                        var element = document.querySelector('[data-translator-id="' + elementId + '"]');

                        if (!element) {
                            console.error('Cannot find element with ID: ' + elementId);
                            return false;
                        }

                        // Check if this element is translated
                        var isTranslated = translatedElements[elementId] || element.hasAttribute('data-translated');

                        if (isTranslated) {
                            // Element is translated, restore original content
                            if (originalContents[elementId]) {
                                // Restore original content
                                element.innerHTML = originalContents[elementId];

                                // Get the original text after restoring content
                                var originalText = element.textContent.trim();

                                // IMPROVED APPROACH: Determine the dominant language
                                var hebrewChars = 0;
                                var nonHebrewChars = 0;

                                // Count Hebrew and non-Hebrew characters
                                for (var i = 0; i < originalText.length; i++) {
                                    var charCode = originalText.charCodeAt(i);
                                    // Hebrew Unicode ranges
                                    if ((charCode >= 0x0590 && charCode <= 0x05FF) ||
                                        (charCode >= 0xFB1D && charCode <= 0xFB4F)) {
                                        hebrewChars++;
                                    } else if (charCode > 32) { // Skip whitespace
                                        nonHebrewChars++;
                                    }
                                }

                                console.log('Hebrew chars: ' + hebrewChars + ', Non-Hebrew chars: ' + nonHebrewChars);

                                // Determine dominant language and set direction
                                if (hebrewChars > nonHebrewChars) {
                                    // Hebrew is dominant, set RTL
                                    console.log('Hebrew is dominant, setting RTL');
                                    element.style.direction = 'rtl';
                                    element.style.textAlign = 'right';
                                } else {
                                    // Non-Hebrew is dominant, set LTR
                                    console.log('Non-Hebrew is dominant, setting LTR');
                                    element.style.direction = 'ltr';
                                    element.style.textAlign = 'left';
                                }

                                // Log the applied styles for debugging
                                console.log('Applied direction: ' + element.style.direction);
                                console.log('Applied text-align: ' + element.style.textAlign);

                                // Restore other styles
                                element.style.borderLeft = styles.borderLeft;
                                element.style.borderRight = styles.borderRight;
                                element.style.paddingLeft = styles.paddingLeft;
                                element.style.paddingRight = styles.paddingRight;
                            }

                            // Remove visual indicator
                            element.style.backgroundColor = '';
                            element.style.border = '';
                            element.style.borderRadius = '';
                            element.style.padding = '';

                            // Mark as not translated
                            window.markElementAsTranslated(elementId, false);
                            console.log('Restored original content');
                            return true;
                        } else {
                            console.log('Element is not translated, nothing to toggle');
                            return false;
                        }
                    };

                    // Helper function to get a simple hash code for a string
                    function hashCode(str) {
                        var hash = 0;
                        if (str.length === 0) return hash;
                        for (var i = 0; i < str.length; i++) {
                            var char = str.charCodeAt(i);
                            hash = ((hash << 5) - hash) + char;
                            hash = hash & hash; // Convert to 32bit integer
                        }
                        return Math.abs(hash).toString(36);
                    }

                    // Helper function to get a path-like identifier for an element
                    function getElementPath(element) {
                        var path = [];
                        var current = element;

                        // Build a simple path based on tag names and positions
                        while (current && current !== document.body) {
                            var tag = current.tagName.toLowerCase();
                            var siblings = Array.from(current.parentNode.children).filter(c => c.tagName === current.tagName);
                            var position = siblings.indexOf(current);

                            path.unshift(tag + (siblings.length > 1 ? position : ''));
                            current = current.parentNode;
                        }

                        return path.join('>');
                    }



                    // Initialize the translation system
                    function initTranslationSystem() {
                        console.log('Initializing translation system...');

                        // We've removed text selection handling to use long-press for translation popup instead

                        // Process all text elements on the page
                        processTextElements();

                        // Restore translated state from data attributes
                        restoreTranslatedState();

                        // Batch DOM mutations so translation updates cannot repeatedly
                        // rescan the entire book while the user is scrolling.
                        var processTimer = null;
                        var observer = new MutationObserver(function(mutations) {
                            if (processTimer !== null) {
                                return;
                            }
                            processTimer = setTimeout(function() {
                                processTimer = null;
                                processTextElements();
                                restoreTranslatedState();
                            }, 100);
                        });

                        observer.observe(document.body, { childList: true, subtree: true });

                        console.log('Translation system initialized');
                        if (window.AndroidTranslator && window.AndroidTranslator.onPageReady) {
                            window.AndroidTranslator.onPageReady();
                        }

                    }

                    // Function to restore translated state from data attributes
                    function restoreTranslatedState() {
                        // Find all elements with data-translated attribute
                        var translatedElems = document.querySelectorAll('[data-translated="true"]');
                        console.log('Found ' + translatedElems.length + ' elements with data-translated attribute');

                        // Mark them as translated in our tracking object
                        for (var i = 0; i < translatedElems.length; i++) {
                            var elem = translatedElems[i];
                            var elemId = elem.getAttribute('data-translator-id');
                            if (elemId) {
                                translatedElements[elemId] = true;
                                console.log('Restored translated state for element: ' + elemId);
                            }
                        }
                    }

                    // Function to replace text in an element (removed - using the one defined later)

                    // Function to restore original text
                    window.restoreOriginalText = function(elementId) {
                        var element = document.querySelector('[data-translator-id="' + elementId + '"]');
                        if (element && originalContents[elementId]) {
                            element.innerHTML = originalContents[elementId];
                            element.style.backgroundColor = '';
                            element.style.borderLeft = '';
                            element.style.paddingLeft = '';
                            delete translatedElements[elementId];
                            return true;
                        }
                        return false;
                    };

                    // Function to restore all original text
                    window.restoreAllOriginalText = function() {
                        for (var id in translatedElements) {
                            window.restoreOriginalText(id);
                        }
                    };

                    // Process all text elements on the page
                    function processTextElements() {
                        console.log('Processing text elements...');

                        // First, prioritize proper block-level paragraph elements
                        // Focus on semantic paragraph tags and common EPUB paragraph containers
                        var paragraphs = document.querySelectorAll('p, h1, h2, h3, h4, h5, h6, figcaption, div.paragraph, div.text, div.para, article > div, section > div, blockquote');
                        var textElements = [];
                        var processedElements = new Set();

                        console.log('Found ' + paragraphs.length + ' potential paragraph elements');

                        // Process paragraph elements first
                        for (var i = 0; i < paragraphs.length; i++) {
                            var element = paragraphs[i];

                            // Skip elements that are clearly not content
                            if (element.classList.contains('hidden') ||
                                getComputedStyle(element).display === 'none' ||
                                getComputedStyle(element).visibility === 'hidden') {
                                continue;
                            }

                            // Check if this element has meaningful text content
                            var text = element.textContent.trim();
                            if (text.length > 0 && !/^\s*$/.test(text)) {
                                // Skip elements that are too small to be meaningful content
                                var rect = element.getBoundingClientRect();
                                if (rect.width < 10 || rect.height < 10) {
                                    continue;
                                }

                                // Ensure this is a block-level element (not inline)
                                var displayStyle = getComputedStyle(element).display;
                                if (displayStyle === 'inline' || displayStyle === 'inline-block') {
                                    console.log('Skipping inline element: ' + element.tagName);
                                    continue;
                                }

                                // Add the paragraph element
                                textElements.push(element);
                                processedElements.add(element);
                                console.log('Added paragraph element: ' + text.substring(0, 30) + '...');
                            }
                        }

                        // Now process other text-containing elements
                        var allElements = document.querySelectorAll('div, section, article, h1, h2, h3, h4, h5, h6, li');

                        // Process elements that might contain text
                        for (var i = 0; i < allElements.length; i++) {
                            var element = allElements[i];

                            // Skip already processed elements
                            if (processedElements.has(element)) {
                                continue;
                            }

                            // Skip elements that are clearly not content
                            if (element.classList.contains('hidden') ||
                                getComputedStyle(element).display === 'none' ||
                                getComputedStyle(element).visibility === 'hidden') {
                                continue;
                            }

                            // Inline children belong to their parent paragraph; do not
                            // give each formatted fragment its own translation box.
                            var displayStyle = getComputedStyle(element).display;
                            if (displayStyle === 'inline' || displayStyle === 'inline-block') {
                                continue;
                            }

                            // Check if this element has meaningful text content
                            var text = element.textContent.trim();
                            if (text.length > 0 && !/^\s*$/.test(text)) {
                                // Check if this is a leaf node or has only simple formatting children
                                var isLeafOrSimple = true;
                                for (var j = 0; j < element.children.length; j++) {
                                    var child = element.children[j];
                                    var childTag = child.tagName.toLowerCase();

                                    // Allow simple formatting tags
                                    if (childTag !== 'b' && childTag !== 'i' && childTag !== 'em' &&
                                        childTag !== 'strong' && childTag !== 'span' && childTag !== 'a' &&
                                        childTag !== 'br' && childTag !== 'small' && childTag !== 'sub' &&
                                        childTag !== 'sup') {
                                        isLeafOrSimple = false;
                                        break;
                                    }
                                }

                                // If this is a text element with content, add it
                                if (isLeafOrSimple) {
                                    textElements.push(element);
                                    console.log('Added text element: ' + text.substring(0, 30) + '...');
                                }
                            }
                        }

                        console.log('Found ' + textElements.length + ' text elements');

                        // Add handlers to each text element
                        for (var i = 0; i < textElements.length; i++) {
                            addHandlersToElement(textElements[i]);
                        }
                    }

                    // Add handlers to a specific element
                    function addHandlersToElement(element) {
                        // Skip elements that already have an ID
                        if (element.hasAttribute('data-translator-id')) {
                            return;
                        }

                        // Skip elements with no text content
                        var text = element.textContent.trim();
                        if (text.length === 0) {
                            return;
                        }

                        // Create a more stable ID based on element content and position
                        // This helps maintain translations when navigating between pages
                        var elementText = element.textContent.trim();
                        var elementPath = getElementPath(element);
                        var contentHash = hashCode(elementText.substring(0, Math.min(50, elementText.length)));
                        // The path length is not unique for sibling list items. Hash the
                        // complete path so every bullet gets its own translation target.
                        var pathHash = hashCode(elementPath);
                        var elementId = 'translator-' + contentHash + '-' + pathHash;
                        element.setAttribute('data-translator-id', elementId);
                        element.setAttribute('data-content-hash', contentHash);

                        // Let links keep their normal WebView navigation behavior.
                        // The paragraph gesture handler must not cancel anchor clicks.
                        var links = element.querySelectorAll('a[href]');
                        for (var linkIndex = 0; linkIndex < links.length; linkIndex++) {
                            let link = links[linkIndex];
                            let touchStartX = 0;
                            let touchStartY = 0;
                            let linkMoved = false;
                            let activateLink = function(event) {
                                event.stopPropagation();
                                event.preventDefault();
                                if (window.AndroidTranslator) {
                                    window.AndroidTranslator.onLinkClick(link.getAttribute('href') || '');
                                }
                            };
                            link.addEventListener('click', activateLink);
                            link.addEventListener('touchstart', function(event) {
                                var touch = event.touches[0];
                                touchStartX = touch.clientX;
                                touchStartY = touch.clientY;
                                linkMoved = false;
                            }, {passive: true});
                            link.addEventListener('touchmove', function(event) {
                                var touch = event.touches[0];
                                if (Math.abs(touch.clientX - touchStartX) > 12 ||
                                    Math.abs(touch.clientY - touchStartY) > 12) {
                                    linkMoved = true;
                                }
                            }, {passive: true});
                            link.addEventListener('touchend', function(event) {
                                if (!linkMoved) {
                                    activateLink(event);
                                }
                            });
                        }

                        // Log the element ID for debugging
                        console.log('Assigned ID: ' + elementId + ' to element with text: ' + elementText.substring(0, 30) + '...');

                        // Enhanced tap handler - single-click to show popup, double-tap to translate/toggle
                        function handleTap(event) {
                            var now = new Date().getTime();
                            var timeSince = now - lastClickTime;
                            var thisElement = this;

                            console.log('handleTap called, time since last: ' + timeSince + 'ms, clickCount: ' + clickCount + ', element: ' + thisElement.tagName);



                            // CRITICAL: Check if this event seems to be part of a scroll/swipe, ignore it
                            if (event && event.type === 'touchend' && globalHasMoved) {
                                console.log('🚫 Ignoring tap - movement was detected during touch (swipe/scroll)');
                                return;
                            }

                            // Enhanced duplicate prevention
                            if (window.processingTap) {
                                console.log('Already processing tap, ignoring duplicate event');
                                if (event) {
                                    event.preventDefault();
                                    event.stopPropagation();
                                }
                                return;
                            }

                            // Check if this is the same element as the last tap
                            var isSameElement = thisElement === lastClickElement;

                            // Define timing windows for double-tap detection (improved timing)
                            var isWithinFastWindow = timeSince < 350 && isSameElement;
                            var isWithinSlowWindow = timeSince >= 350 && timeSince < 600 && isSameElement;

                            if (isWithinFastWindow || isWithinSlowWindow) {
                                // This could be the second tap of a double-tap
                                clickCount++;
                                console.log('Potential second tap detected, clickCount: ' + clickCount);

                                if (clickCount === 2) {
                                    // This is definitely a double-tap
                                    console.log('✅ DOUBLE-TAP CONFIRMED - triggering translation/toggle');

                                    // Set processing flag to prevent duplicate events
                                    window.processingTap = true;

                                    // Clear any existing timeout
                                    if (window.tapTimeout) {
                                        clearTimeout(window.tapTimeout);
                                    }

                                    // Trigger translation/toggle immediately
                                    handleInlineTranslation(thisElement, event);

                                    // Reset state
                                    clickCount = 0;
                                    lastClickTime = 0;
                                    lastClickElement = null;

                                    // Clear processing flag after a short delay
                                    setTimeout(function() {
                                        window.processingTap = false;
                                    }, 300);

                                    return;
                                }
                            } else {
                                // This is either a first tap or a tap after timeout
                                clickCount = 1;
                                console.log('First tap detected, setting up single-click action');

                                // Clear any existing timeout
                                if (window.tapTimeout) {
                                    clearTimeout(window.tapTimeout);
                                }

                                // Set a timeout to handle single-click action if no second tap comes
                                window.tapTimeout = setTimeout(function() {
                                    if (clickCount === 1) {
                                        console.log('✅ SINGLE-CLICK CONFIRMED - showing popup menu');

                                        // Set processing flag to prevent duplicate events
                                        window.processingTap = true;

                                        // Handle single-click action (show popup menu)
                                        showParagraphPopup(thisElement, event);

                                        // Clear processing flag after a short delay
                                        setTimeout(function() {
                                            window.processingTap = false;
                                        }, 300);
                                    }

                                    // Reset state
                                    clickCount = 0;
                                    lastClickTime = 0;
                                    lastClickElement = null;
                                }, 400); // 400ms timeout for single-click detection (improved timing)
                            }

                            // Update tracking variables
                            lastClickTime = now;
                            lastClickElement = thisElement;
                        }

                        // Use only one primary event handler to prevent duplicate triggers
                        // Prioritize touch events for mobile, click events for desktop
                        var isTouchDevice = 'ontouchstart' in window || navigator.maxTouchPoints > 0;

                        if (isTouchDevice) {
                            // For touch devices, use a combined touch handler with swipe detection
                            var touchStartTime = 0;
                            var touchStartElement = null;

                            element.addEventListener('touchstart', function(event) {
                                touchStartTime = new Date().getTime();
                                touchStartElement = this;

                                // Record initial touch position and time globally for swipe detection
                                if (event.touches && event.touches.length > 0) {
                                    globalTouchStartX = event.touches[0].clientX;
                                    globalTouchStartY = event.touches[0].clientY;
                                    globalTouchStartTime = touchStartTime;
                                }
                                globalHasMoved = false;

                            });

                            element.addEventListener('touchmove', function(event) {
                                // Check if this is a significant movement (swipe)
                                if (event.touches && event.touches.length > 0) {
                                    var currentX = event.touches[0].clientX;
                                    var currentY = event.touches[0].clientY;

                                    var deltaX = Math.abs(currentX - globalTouchStartX);
                                    var deltaY = Math.abs(currentY - globalTouchStartY);

                                    // If movement is more than threshold, consider it a swipe
                                    if (deltaX > MOVEMENT_THRESHOLD || deltaY > MOVEMENT_THRESHOLD) {
                                        globalHasMoved = true;
                                        console.log('🔄 Touch movement detected: deltaX=' + deltaX + ', deltaY=' + deltaY + ' (threshold=' + MOVEMENT_THRESHOLD + ')');
                                    }
                                }
                            });

                            element.addEventListener('touchend', function(event) {
                                // Check if this was a quick touch (not a long press) and not a swipe
                                var touchDuration = new Date().getTime() - touchStartTime;
                                if (touchDuration < 800 && this === touchStartElement && !globalHasMoved) {
                                    // This is a tap, not a long press or swipe
                                    console.log('✅ Valid tap detected (no movement, duration: ' + touchDuration + 'ms)');
                                    event.preventDefault();
                                    event.stopPropagation();
                                    handleTap.call(this, event);
                                } else if (globalHasMoved) {
                                    console.log('🚫 Touch ignored - movement detected (swipe/scroll)');
                                } else if (touchDuration >= 800) {
                                    console.log('🚫 Touch ignored - long press detected (' + touchDuration + 'ms)');
                                }
                            });

                            console.log('Added touch handlers for touch device');
                        } else {
                            // For non-touch devices, use click
                            element.addEventListener('click', function(event) {
                                handleTap.call(this, event);
                            });
                            element.addEventListener('dblclick', function(event) {
                                // Direct fallback for platforms emitting dblclick
                                handleInlineTranslation(this, event);
                            });
                            console.log('Added click/dblclick handlers for non-touch device');
                        }
                    }

                    // Show popup menu for paragraph actions
                    function showParagraphPopup(element, event) {
                        console.log('showParagraphPopup called for element:', element.tagName, element.textContent.substring(0, 30) + '...');

                        // Prevent any default behavior and stop propagation
                        if (event) {
                            event.preventDefault();
                            event.stopPropagation();
                        }

                        var elementId = element.getAttribute('data-translator-id');

                        // Get the text content
                        var paragraphText = '';

                        // If element is translated, get the original text for the popup
                        if (element.hasAttribute('data-translated') && originalContents[elementId]) {
                            // Create a temporary element to extract text from HTML
                            var tempDiv = document.createElement('div');
                            tempDiv.innerHTML = originalContents[elementId];
                            paragraphText = tempDiv.textContent.trim();
                            console.log('Using original text from translated element for popup');
                        } else {
                            // Get current text content
                            paragraphText = element.textContent.trim();
                            console.log('Using current text content for popup');
                        }

                        // Enhanced text extraction for better EPUB compatibility
                        if (paragraphText.length < 10) {
                            console.log('Text too short, trying alternative extraction methods');

                            // Try to get text from child elements
                            var childText = '';
                            for (var i = 0; i < element.children.length; i++) {
                                childText += element.children[i].textContent + ' ';
                            }
                            if (childText.trim().length > paragraphText.length) {
                                paragraphText = childText.trim();
                                console.log('Using child element text for popup');
                            }
                        }

                        if (paragraphText.length > 0) {
                            console.log('Showing popup menu for text: ' + paragraphText.substring(0, 50) + '...');

                            // Get element position for popup placement
                            var rect = element.getBoundingClientRect();
                            var centerX = rect.left + (rect.width / 2);
                            var rectTop = rect.top;
                            var rectBottom = rect.bottom;

                            // Send the text and precise rect to Android for smarter popup positioning
                            if (window.AndroidTranslator.onShowParagraphPopupV2) {
                                window.AndroidTranslator.onShowParagraphPopupV2(paragraphText, elementId || '', centerX, rectTop, rectBottom);
                            } else {
                                // Fallback to legacy API
                                window.AndroidTranslator.onShowParagraphPopup(paragraphText, elementId || '', centerX, rectBottom + 10);
                            }
                        } else {
                            console.log('No text found for popup');
                        }
                    }

                    // Handle inline translation of an element (double-tap)
                    function handleInlineTranslation(element, event) {
                        console.log('handleInlineTranslation called for element:', element.tagName, element.textContent.substring(0, 30) + '...');

                        var elementId = element.getAttribute('data-translator-id');

                        if (!elementId) {
                            console.error('Element has no translator ID, attempting to find parent with ID');
                            // Try to find a parent element with translator ID
                            var parent = element.parentElement;
                            while (parent && !elementId) {
                                elementId = parent.getAttribute('data-translator-id');
                                if (elementId) {
                                    element = parent;
                                    console.log('Found translator ID in parent element:', parent.tagName);
                                    break;
                                }
                                parent = parent.parentElement;
                            }

                            if (!elementId) {
                                console.error('No translator ID found in element or parents');
                                return;
                            }
                        }

                        // Prevent default behavior and stop propagation
                        if (event) {
                            event.preventDefault();
                            event.stopPropagation();
                        }

                        // SIMPLIFIED APPROACH: Check if element has the data-translated attribute
                        if (element.hasAttribute('data-translated')) {
                            console.log('Element is already translated, toggling back to original');

                            // DIRECT APPROACH: Restore original content immediately.
                            // Use the DOM snapshot as a fallback after page/state restoration.
                            var savedOriginalHtml = originalContents[elementId] || element.getAttribute('data-original-html');
                            if (savedOriginalHtml) {
                                // Restore original content
                                element.innerHTML = savedOriginalHtml;

                                // Restore original styles
                                if (originalStyles[elementId]) {
                                    var styles = originalStyles[elementId];

                                    // Get the original text after restoring content
                                    var originalText = element.textContent.trim();

                                    // IMPROVED APPROACH: Determine the dominant language
                                    var hebrewChars = 0;
                                    var nonHebrewChars = 0;

                                    // Count Hebrew and non-Hebrew characters
                                    for (var i = 0; i < originalText.length; i++) {
                                        var charCode = originalText.charCodeAt(i);
                                        // Hebrew Unicode ranges
                                        if ((charCode >= 0x0590 && charCode <= 0x05FF) ||
                                            (charCode >= 0xFB1D && charCode <= 0xFB4F)) {
                                            hebrewChars++;
                                        } else if (charCode > 32) { // Skip whitespace
                                            nonHebrewChars++;
                                        }
                                    }

                                    console.log('Hebrew chars: ' + hebrewChars + ', Non-Hebrew chars: ' + nonHebrewChars);

                                    // Determine dominant language and set direction
                                    if (hebrewChars > nonHebrewChars) {
                                        // Hebrew is dominant, set RTL
                                        console.log('Hebrew is dominant, setting RTL');
                                        element.style.direction = 'rtl';
                                        element.style.textAlign = 'right';
                                    } else {
                                        // Non-Hebrew is dominant, set LTR
                                        console.log('Non-Hebrew is dominant, setting LTR');
                                        element.style.direction = 'ltr';
                                        element.style.textAlign = 'left';
                                    }

                                    // Log the applied styles for debugging
                                    console.log('Applied direction: ' + element.style.direction);
                                    console.log('Applied text-align: ' + element.style.textAlign);

                                    // Restore other styles
                                    element.style.borderLeft = styles.borderLeft;
                                    element.style.borderRight = styles.borderRight;
                                    element.style.paddingLeft = styles.paddingLeft;
                                    element.style.paddingRight = styles.paddingRight;
                                }

                                // Remove visual indicator
                                element.style.backgroundColor = '';
                                element.style.border = '';
                                element.style.borderRadius = '';
                                element.style.padding = '';

                                // Update state tracking
                                delete translatedElements[elementId];
                                element.removeAttribute('data-translated');
                                element.classList.remove('translator-translated');

                                // Get the original text to notify Android
                                var tempDiv = document.createElement('div');
                                tempDiv.innerHTML = savedOriginalHtml;
                                var originalText = tempDiv.textContent.trim();

                                // Notify Android to update its state
                                window.AndroidTranslator.onToggleTranslation(originalText, elementId);
                                console.log('Successfully restored original content');
                            } else {
                                console.error('Original content not found for element: ' + elementId);
                            }
                        }

                        // If we get here, either the element is not translated or we couldn't find the original content
                        // Get the complete text content of the clicked element, preserving ALL content
                        var paragraphText = element.textContent.trim();

                        // Enhanced text extraction for better EPUB compatibility
                        if (paragraphText.length < 10) {
                            console.log('Text too short, trying alternative extraction methods');

                            // Try to get text from child elements
                            var childText = '';
                            for (var i = 0; i < element.children.length; i++) {
                                childText += element.children[i].textContent + ' ';
                            }
                            if (childText.trim().length > paragraphText.length) {
                                paragraphText = childText.trim();
                                console.log('Using child element text:', paragraphText.substring(0, 30) + '...');
                            }

                            // If still too short, try innerText
                            if (paragraphText.length < 10 && element.innerText) {
                                paragraphText = element.innerText.trim();
                                console.log('Using innerText:', paragraphText.substring(0, 30) + '...');
                            }

                            // If still too short, try innerHTML and strip tags
                            if (paragraphText.length < 10 && element.innerHTML) {
                                var tempDiv = document.createElement('div');
                                tempDiv.innerHTML = element.innerHTML;
                                var strippedText = tempDiv.textContent || tempDiv.innerText || '';
                                if (strippedText.trim().length > paragraphText.length) {
                                    paragraphText = strippedText.trim();
                                    console.log('Using stripped HTML text:', paragraphText.substring(0, 30) + '...');
                                }
                            }
                        }

                        if (paragraphText.length > 0) {

                            // Log the full paragraph for debugging
                            console.log('FULL PARAGRAPH: ' + paragraphText);
                            console.log('PARAGRAPH LENGTH: ' + paragraphText.length + ' characters');

                            // Check if this paragraph contains any special symbols and log it
                            if (/[\"\'\.\,\!\?\:\;\(\)\[\]\{\}\-\_\@\#\$\%\^\&\*\+\=\~\`\|\<\>]/.test(paragraphText)) {
                                console.log('Translating paragraph WITH SYMBOLS: ' + paragraphText.substring(0, 30) + '...');
                            } else {
                                console.log('Inline translating paragraph: ' + paragraphText.substring(0, 30) + '...');
                            }

                            // Send the paragraph text to Android for inline translation
                            // The entire paragraph is sent as a single unit, including any quotation marks
                            window.AndroidTranslator.onParagraphDoubleClick(elementId, paragraphText);
                        }
                    }

                    // Handle dialog translation of an element (triple-tap)
                    function handleDialogTranslation(element, event) {
                        // Get the complete text content of the clicked element, preserving ALL quotation marks
                        var paragraphText = element.textContent.trim();
                        var elementId = element.getAttribute('data-translator-id');

                        if (paragraphText.length > 0 && elementId) {
                            // Prevent default behavior and stop propagation
                            event.preventDefault();
                            event.stopPropagation();

                            // Check if this paragraph contains any special symbols and log it
                            if (/[\"\'\.\,\!\?\:\;\(\)\[\]\{\}\-\_\@\#\$\%\^\&\*\+\=\~\`\|\<\>]/.test(paragraphText)) {
                                console.log('Dialog translating paragraph WITH SYMBOLS: ' + paragraphText.substring(0, 30) + '...');
                            } else {
                                console.log('Dialog translating paragraph: ' + paragraphText.substring(0, 30) + '...');
                            }

                            // Send the paragraph text to Android for dialog translation
                            // The entire paragraph is sent as a single unit, including any quotation marks
                            window.AndroidTranslator.onParagraphTripleTapped(paragraphText, elementId);
                        }
                    }

                    // Highlight text with a specific color
                    window.highlightText = function(color) {
                        if (!currentSelection) return false;

                        try {
                            var range = currentSelection.range;
                            var span = document.createElement('span');
                            span.style.backgroundColor = color;
                            span.className = 'highlighted-text';

                            // Create a unique ID for this highlight
                            var highlightId = 'highlight-' + Math.random().toString(36).substr(2, 9);
                            span.setAttribute('data-highlight-id', highlightId);

                            // Store the highlight in our tracking object
                            highlightedElements[highlightId] = {
                                color: color,
                                text: currentSelection.text
                            };

                            // Apply the highlight
                            range.surroundContents(span);

                            // Clear the selection
                            window.getSelection().removeAllRanges();
                            currentSelection = null;

                            return true;
                        } catch (e) {
                            console.error('Error highlighting text:', e);
                            return false;
                        }
                    };

                    // Remove highlight from an element
                    window.removeHighlight = function(highlightId) {
                        var span = document.querySelector('[data-highlight-id="' + highlightId + '"]');
                        if (span) {
                            // Replace the span with its text content
                            var text = document.createTextNode(span.textContent);
                            span.parentNode.replaceChild(text, span);
                            delete highlightedElements[highlightId];
                            return true;
                        }
                        return false;
                    };

                    // Replace element text with translated text - preserving all symbols
                    window.replaceElementText = function(elementId, originalText, translatedText) {
                        var element = document.querySelector('[data-translator-id="' + elementId + '"]');
                        if (!element) {
                            console.error('Element not found: ' + elementId);
                            return false;
                        }

                        console.log('Replacing text in element: ' + elementId);

                        // Check if this is a toggle request (empty strings passed)
                        var isToggleRequest = originalText === '' && translatedText === '';

                        if (!isToggleRequest) {
                            console.log('Original text: ' + originalText.substring(0, 50) + '...');
                            console.log('Translated text: ' + translatedText.substring(0, 50) + '...');
                        } else {
                            console.log('Toggle request detected');
                        }

                        // Store original content and styles if not already stored
                        if (!originalContents[elementId]) {
                            originalContents[elementId] = element.innerHTML;
                            originalStyles[elementId] = {
                                direction: element.style.direction || 'ltr',
                                textAlign: element.style.textAlign || 'left',
                                borderLeft: element.style.borderLeft || '',
                                borderRight: element.style.borderRight || '',
                                paddingLeft: element.style.paddingLeft || '',
                                paddingRight: element.style.paddingRight || ''
                            };
                        }

                        // Only toggle back to original if explicitly requested with empty strings
                        // This prevents automatic toggling when navigating between pages
                        if (isToggleRequest && translatedElements[elementId]) {
                            // Toggle back to original content
                            element.innerHTML = originalContents[elementId];
                            // Remove visual indicators
                            element.style.backgroundColor = '';

                            // Reset alignment to original or based on original content
                            var tempDiv = document.createElement('div');
                            tempDiv.innerHTML = originalContents[elementId];
                            var originalTextContent = tempDiv.textContent;

                            // If a target language was stored, clear styles; otherwise fallback to content-based detection
                            var targetLang = element.getAttribute('data-target-language');
                            if (targetLang) {
                                element.style.direction = '';
                                element.style.textAlign = '';
                                element.style.borderRight = '';
                                element.style.paddingRight = '';
                                element.style.borderLeft = '';
                                element.style.paddingLeft = '';
                            } else {
                                // Content-based detection fallback
                                var rtlRegex = /[\u0590-\u05FF\uFB1D-\uFB4F\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/g;
                                var cyrillicRegex = /[\u0400-\u04FF]/g;
                                var latinRegex = /[a-zA-Z]/g;
                                var rtlChars = (originalTextContent.match(rtlRegex) || []).length;
                                var cyrillicChars = (originalTextContent.match(cyrillicRegex) || []).length;
                                var latinChars = (originalTextContent.match(latinRegex) || []).length;
                                var isRTL = rtlChars > 0 && rtlChars > (cyrillicChars + latinChars) * 0.4 && cyrillicChars === 0;
                                if (isRTL) {
                                    element.style.direction = 'rtl';
                                    element.style.textAlign = 'right';
                                    element.style.borderRight = '';
                                    element.style.paddingRight = '';
                                    element.style.borderLeft = '';
                                    element.style.paddingLeft = '';
                                } else {
                                    element.style.direction = 'ltr';
                                    element.style.textAlign = 'left';
                                    element.style.borderLeft = '3px solid green';
                                    element.style.paddingLeft = '5px';
                                }
                            }

                            // Mark element as not translated
                            window.markElementAsTranslated(elementId, false);
                            console.log('Restored original text');
                        } else {
                            // Log the full translated text for debugging
                            console.log('FULL TRANSLATED TEXT: ' + translatedText);
                            console.log('TRANSLATED TEXT LENGTH: ' + translatedText.length + ' characters');

                            // PRESERVE IMAGES: Replace only text content while keeping HTML structure
                            // First, store the original HTML if not already stored
                            if (!originalContents[elementId]) {
                                originalContents[elementId] = element.innerHTML;
                            }

                            // Create a temporary div to work with the original HTML
                            var tempDiv = document.createElement('div');
                            tempDiv.innerHTML = originalContents[elementId];

                            // Replace the complete text across nested nodes while preserving
                            // markup such as emphasis, links, and images.
                            var textNodes = [];
                            function collectTextNodes(node) {
                                if (node.nodeType === Node.TEXT_NODE) {
                                    if (node.textContent.trim().length > 0) {
                                        textNodes.push(node);
                                    }
                                } else if (node.nodeType === Node.ELEMENT_NODE) {
                                    for (var i = 0; i < node.childNodes.length; i++) {
                                        collectTextNodes(node.childNodes[i]);
                                    }
                                }
                            }

                            function replaceTextInNode(node, translatedText) {
                                collectTextNodes(node);
                                var originalLength = 0;
                                for (var i = 0; i < textNodes.length; i++) {
                                    originalLength += textNodes[i].textContent.length;
                                }
                                var consumed = 0;
                                for (var i = 0; i < textNodes.length; i++) {
                                    var start = Math.floor((consumed / originalLength) * translatedText.length);
                                    consumed += textNodes[i].textContent.length;
                                    var end = i === textNodes.length - 1
                                        ? translatedText.length
                                        : Math.floor((consumed / originalLength) * translatedText.length);
                                    textNodes[i].textContent = translatedText.substring(start, end);
                                }
                            }

                            replaceTextInNode(tempDiv, translatedText);

                            // Update the element with the modified HTML (preserving images)
                            element.innerHTML = tempDiv.innerHTML;

                            // Prefer alignment based on target language if provided
                            (function(){
                                var rtlLangs = ['he','ar','fa','ur'];
                                var lang = element.getAttribute('data-target-language');
                                if (lang && rtlLangs.indexOf(lang) !== -1) {
                                    element.style.direction = 'rtl';
                                    element.style.textAlign = 'right';
                                    element.style.borderRight = '3px solid green';
                                    element.style.paddingRight = '5px';
                                    element.style.borderLeft = '';
                                    element.style.paddingLeft = '';
                                } else if (lang) {
                                    element.style.direction = 'ltr';
                                    element.style.textAlign = 'left';
                                    element.style.borderLeft = '3px solid green';
                                    element.style.paddingLeft = '5px';
                                } else {
                                    // Fallback to content-based detection
                                    var rtlRegex = /[\u0590-\u05FF\uFB1D-\uFB4F\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]/g;
                                    var cyrillicRegex = /[\u0400-\u04FF]/g;
                                    var latinRegex = /[a-zA-Z]/g;
                                    var rtlChars = (translatedText.match(rtlRegex) || []).length;
                                    var cyrillicChars = (translatedText.match(cyrillicRegex) || []).length;
                                    var latinChars = (translatedText.match(latinRegex) || []).length;
                                    var isRTL = rtlChars > 0 && rtlChars > (cyrillicChars + latinChars) * 0.4 && cyrillicChars === 0;
                                    if (isRTL) {
                                        element.style.direction = 'rtl';
                                        element.style.textAlign = 'right';
                                        element.style.borderRight = '3px solid green';
                                        element.style.paddingRight = '5px';
                                        element.style.borderLeft = '';
                                        element.style.paddingLeft = '';
                                    } else {
                                        element.style.direction = 'ltr';
                                        element.style.textAlign = 'left';
                                        element.style.borderLeft = '3px solid green';
                                        element.style.paddingLeft = '5px';
                                    }
                                }
                            })();

                            // Add visual indicators
                            element.style.backgroundColor = 'rgba(144, 238, 144, 0.2)';
                            // Mark element as translated
                            window.markElementAsTranslated(elementId, true);
                            console.log('Applied translation');
                        }

                        return true;
                    };

                    // We're removing the document-level click handler that was clearing selections
                    // This allows text selection to work properly

                    // Initialize the translation system when the page is ready
                    if (document.readyState === 'complete' || document.readyState === 'interactive') {
                        initTranslationSystem();
                    } else {
                        document.addEventListener('DOMContentLoaded', initTranslationSystem);
                    }

                    // Also initialize immediately to catch any missed elements
                    initTranslationSystem();

                    // Check for images and log their status
                    var images = document.querySelectorAll('img');
                    console.log('Found ' + images.length + ' images on page');
                    for (var i = 0; i < images.length; i++) {
                        var img = images[i];
                        console.log('Image ' + i + ': src=' + img.src + ', complete=' + img.complete + ', naturalWidth=' + img.naturalWidth);

                        // Add error handler for images
                        img.onerror = function() {
                            console.error('Failed to load image: ' + this.src);
                        };

                        img.onload = function() {
                            console.log('Successfully loaded image: ' + this.src);
                        };
                    }
                """.trimIndent()

        binding.webView.evaluateJavascript(jsCode, null)
        Log.d(TAG, "JavaScript handlers injected successfully")
    }


    /**
     * Show translation dialog
     */
    private fun setupTranslationPopupActions(
        popupView: View,
        popupWindow: android.widget.PopupWindow,
        paragraphId: String,
        paragraphText: String
    ) {
        popupView.findViewById<View>(R.id.translateHereButton)?.setOnClickListener {
            popupWindow.dismiss()
            handleDoubleClickTranslation(paragraphId, paragraphText)
        }
        popupView.findViewById<View>(R.id.googleTranslateButton).setOnClickListener {
            openExternalTranslationApp(paragraphText, "com.google.android.apps.translate")
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.yandexTranslateButton).setOnClickListener {
            openExternalTranslationApp(paragraphText, "ru.yandex.translate")
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.googleTranslateBubble).setOnClickListener {
            openExternalTranslationBubble(paragraphText, paragraphId, "com.google.android.apps.translate")
            popupWindow.dismiss()
        }
        popupView.findViewById<View>(R.id.yandexTranslateBubble).setOnClickListener {
            openExternalTranslationBubble(paragraphText, paragraphId, "ru.yandex.translate")
            popupWindow.dismiss()
        }
    }

    private fun openExternalTranslationBubble(
        paragraphText: String,
        paragraphId: String,
        packageName: String
    ) {
        try {
            val targetLanguage = translationManager.getTargetLanguage().code
            val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, paragraphText)
                putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                putExtra("original_text", paragraphText)
                putExtra("paragraph_id", paragraphId)
                if (packageName == "com.google.android.apps.translate") {
                    putExtra("com.google.android.apps.translate.api.EXTRA_FROM_LANGUAGE", "auto")
                    putExtra("com.google.android.apps.translate.api.EXTRA_TO_LANGUAGE", targetLanguage)
                } else {
                    putExtra("ru.yandex.translate.extra.TARGET_LANG", targetLanguage)
                    putExtra("TARGET_LANG", targetLanguage)
                }
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            openExternalTranslationApp(paragraphText, packageName)
        }
    }

    private fun consumeTranslationCredit(): Boolean {
        val preferences = getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        val credits = preferences.getInt("translation_credits", 200)
        if (credits <= 0) {
            Toast.makeText(this, "No translation credits remaining", Toast.LENGTH_SHORT).show()
            return false
        }

        preferences.edit().putInt("translation_credits", credits - 1).apply()
        updateCreditsDisplay(credits - 1)
        return true
    }

    private fun showTranslationDialog(paragraphId: String, paragraphText: String) {
        if (!consumeTranslationCredit()) return

        TranslationDialog(
            this,
            paragraphText,
            translationManager,
            onShowInBook = { translatedText ->
                showTranslatedTextInBook(paragraphId, paragraphText, translatedText)
            },
            onTranslationFailed = { refundTranslationCredit() }
        ).show()
    }

    private fun showTranslatedTextInBook(paragraphId: String, originalText: String, translatedText: String) {
        val targetLanguage = translationManager.getTargetLanguage().code
        pageTranslations.getOrPut(currentPage) { mutableMapOf() }[paragraphId] = translatedText
        pageTranslationTargetLanguages.getOrPut(currentPage) { mutableMapOf() }[paragraphId] = targetLanguage
        pageTranslationMethods.getOrPut(currentPage) { mutableMapOf() }[paragraphId] = TranslationMethod.DEFAULT
        toggleTranslationVisibility(paragraphId, true)
    }

    private fun refundTranslationCredit() {
        val preferences = getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
        val credits = preferences.getInt("translation_credits", 200) + 1
        preferences.edit().putInt("translation_credits", credits).apply()
        updateCreditsDisplay(credits)
    }

    private fun updateCreditsDisplay(credits: Int) {
        binding.creditsTextView.text = getString(R.string.translation_credits_count, credits)
    }

    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // If bars are hidden, show them instead of exiting
                if (!barsVisible) {
                    showBars()
                    return
                }

                // Save the current position before exiting
                saveCurrentPosition()
                // Save all translations before clearing
                saveAllTranslations()
                // Clear all translations and cache when exiting
                clearAllTranslationsAndCache()
                finish()
            }
        })
    }

    /**
     * Clear all stored translations and cache
     */
    private fun clearAllTranslationsAndCache() {
        Log.d(TAG, "Clearing all stored translations and cache")
        // Clear translations and visibility state (but they should already be saved)
        pageTranslations.clear()
        pageVisibleTranslations.clear()
        pageTranslationMethods.clear()
        pageTranslationTargetLanguages.clear()
        needToReapplyTranslations = false

        // We don't use HTML cache files anymore

        // Clear image cache
        val imageCache = File(cacheDir, "epub_images")
        if (imageCache.exists()) {
            // Delete all image cache files to ensure no cache is stored
            imageCache.listFiles()?.forEach { file ->
                file.delete()
                Log.d(TAG, "Deleted image cache file: ${file.absolutePath}")
            }
        }

        // Clear WebView cache
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.webView.clearFormData()
        Log.d(TAG, "Cleared WebView cache")

        // Clear application cache directories
        clearApplicationCache()
    }

    /**
     * Clear application cache directories
     */
    private fun clearApplicationCache() {
        try {
            // Clear internal cache directory
            val cacheDir = cacheDir
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isDirectory) {
                            // Skip deleting the epub_images directory as we handle it separately
                            if (file.name != "epub_images") {
                                deleteRecursive(file)
                            }
                        } else {
                            file.delete()
                        }
                    }
                }
            }
            Log.d(TAG, "Cleared application cache directories")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing application cache", e)
        }
    }

    /**
     * Delete a directory and all its contents recursively
     */
    private fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            val children = fileOrDirectory.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursive(child)
                }
            }
        }
        fileOrDirectory.delete()
    }

    override fun onDestroy() {
        try {
            Log.d(TAG, "Starting onDestroy cleanup")

            // Cancel any pending coroutines to prevent memory leaks
            try {
                // Cancel all running coroutines in the current scope
                // Note: Since we're using CoroutineScope(Dispatchers.Main), we need to track and cancel them
                Log.d(TAG, "Cancelling pending coroutines")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling coroutines", e)
            }

            // Clear all translations and cache when the activity is destroyed
            try {
                clearAllTranslationsAndCache()
                Log.d(TAG, "Cleared translations and cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing translations and cache", e)
            }

            // Enhanced WebView cleanup to prevent memory leaks
            try {
                if (::binding.isInitialized) {
                    // Stop all WebView operations first
                    binding.webView.stopLoading()

                    // Remove JavaScript interface to prevent memory leaks
                    binding.webView.removeJavascriptInterface(JS_INTERFACE_NAME)

                    // Clear all WebView data and caches
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                    binding.webView.clearFormData()
                    binding.webView.clearSslPreferences()

                    // Load blank page to clear current content
                    binding.webView.loadUrl("about:blank")

                    // Pause WebView to stop any background processing
                    binding.webView.onPause()

                    // Remove all views from WebView
                    binding.webView.removeAllViews()

                    // Clear drawing cache
                    binding.webView.destroyDrawingCache()

                    // Remove WebView from parent before destroying
                    (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)

                    // Finally destroy the WebView
                    binding.webView.destroy()

                    Log.d(TAG, "Enhanced WebView cleanup completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanced WebView cleanup", e)
            }

            // Clear WebView cookies and storage
            try {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()

                // Clear WebView storage
                android.webkit.WebStorage.getInstance().deleteAllData()

                Log.d(TAG, "Cleared WebView cookies and storage")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing WebView cookies and storage", e)
            }

            // Clear any remaining references
            try {
                synchronized(translationLock) {
                    pendingTranslations.clear()
                    Log.d(TAG, "Cleared pending translations")
                }
                htmlFiles.clear()
                Log.d(TAG, "Cleared remaining data structures")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing data structures", e)
            }

            Log.d(TAG, "onDestroy cleanup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onDestroy", e)
        } finally {
            super.onDestroy()
        }
    }

    // Variable to store the scroll position for each page
    private val pageScrollPositions = mutableMapOf<Int, Int>()

    // Data structure to store translations for each page
    // Map of page index to a map of element text to translated text
    // Using the text content as the key instead of element ID for more reliable matching
    private val pageTranslations = mutableMapOf<Int, MutableMap<String, String>>()

    // Data structure to store which translations are currently visible
    // Map of page index to a set of original texts that should be shown as translated
    private val pageVisibleTranslations = mutableMapOf<Int, MutableSet<String>>()

    // Data structure to store target language for each translation
    // Map of page index to a map of original text to target language code
    private val pageTranslationTargetLanguages = mutableMapOf<Int, MutableMap<String, String>>()

    // Data structure to store translation method for each translation
    // Map of page index to a map of paragraph ID to translation method
    private val pageTranslationMethods = mutableMapOf<Int, MutableMap<String, TranslationMethod>>()

    // Flag to track if we need to reapply translations after page load
    private var needToReapplyTranslations = false

    /**
     * Preload all saved scroll positions from persistent storage
     */
    private fun preloadScrollPositions() {
        epubFilePath?.let { path ->
            // Clear existing positions
            pageScrollPositions.clear()

            // Load positions for all pages
            for (i in 0 until totalPages) {
                val pageScrollY = bookPositionManager.getScrollPosition("${path}_page_$i")
                if (pageScrollY > 0) {
                    pageScrollPositions[i] = pageScrollY
                    Log.d(TAG, "Preloaded scroll position for page $i: $pageScrollY")
                }
            }

            Log.d(TAG, "Preloaded ${pageScrollPositions.size} page positions")
        }
    }

    /**
     * Preload all saved translations from persistent storage
     */
    private fun preloadTranslations() {
        epubFilePath?.let { path ->
            // Clear existing translations and visibility state in thread-safe manner
            synchronized(pageTranslations) {
                pageTranslations.clear()
            }
            synchronized(pageVisibleTranslations) {
                pageVisibleTranslations.clear()
            }
            synchronized(pageTranslationTargetLanguages) {
                pageTranslationTargetLanguages.clear()
            }
            synchronized(pageTranslationMethods) {
                pageTranslationMethods.clear()
            }

            // Load all translations, visibility state, and target languages for this book
            val (allTranslations, allVisibleTranslations, allTargetLanguages) = bookPositionManager.loadAllBookTranslations(path, totalPages)

            synchronized(pageTranslations) {
                pageTranslations.putAll(allTranslations)
            }
            synchronized(pageVisibleTranslations) {
                pageVisibleTranslations.putAll(allVisibleTranslations)
            }
            synchronized(pageTranslationTargetLanguages) {
                pageTranslationTargetLanguages.putAll(allTargetLanguages)
            }

            // For backward compatibility: if target languages are missing, don't assume current language
            // This prevents the language switching bug by not making assumptions about old translations
            val currentTargetLanguage = translationManager.getTargetLanguage().code
            for ((pageNum, translations) in allTranslations) {
                synchronized(pageTranslationTargetLanguages) {
                    if (!pageTranslationTargetLanguages.containsKey(pageNum)) {
                        // Only for completely missing language data, create empty map
                        // This allows the translation system to detect language changes properly
                        pageTranslationTargetLanguages[pageNum] = mutableMapOf()
                    }
                }
            }

            val translationCount = synchronized(pageTranslations) { pageTranslations.size }
            Log.d(TAG, "Preloaded translations for $translationCount pages with visibility state and target languages")
        }
    }

    /**
     * Save all current translations to persistent storage
     */
    private fun saveAllTranslations() {
        epubFilePath?.let { path ->
            if (pageTranslations.isNotEmpty()) {
                // Save translations along with visibility and target languages for full fidelity
                bookPositionManager.saveAllBookTranslations(
                    path,
                    pageTranslations,
                    pageVisibleTranslations,
                    pageTranslationTargetLanguages
                )
                Log.d(TAG, "Saved all translations for ${pageTranslations.size} pages with visibility state and target languages")
            }
        }
    }

    /**
     * Set up gesture detector for swipe navigation
     */
    private fun setupGestureDetector() {
        // Set up a simple touch listener for the WebView
        var startX = 0f
        var startY = 0f
        var longPressTriggered = false
        var selectionMovement = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            longPressTriggered = true
        }

        binding.webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    longPressTriggered = false
                    selectionMovement = false
                    longPressHandler.postDelayed(
                        longPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressTriggered) {
                        val movementX = event.x - startX
                        val movementY = event.y - startY
                        if (Math.abs(movementX) > 20 || Math.abs(movementY) > 20) {
                            selectionMovement = true
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    val endX = event.x
                    val endY = event.y

                    val diffX = endX - startX
                    val diffY = endY - startY

                    // Check if it's a horizontal swipe (improved detection)
                    val isHorizontalSwipe = Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 120
                    val isSignificantMovement = Math.abs(diffX) > 50 || Math.abs(diffY) > 50

                    if (longPressTriggered && !selectionMovement && !isSignificantMovement) {
                        clearWebViewSelection()
                        toggleBars()
                        return@setOnTouchListener true
                    }

                    if (!longPressTriggered && !isSignificantMovement) {
                        clearWebViewSelection()
                    }

                    if (!longPressTriggered && isHorizontalSwipe && isSignificantMovement) {
                        // Always save the current scroll position for the current page
                        val currentScrollY = binding.webView.scrollY
                        pageScrollPositions[currentPage] = currentScrollY
                        Log.d(TAG, "Saved scroll position for page $currentPage: $currentScrollY")

                        // Also save to persistent storage for backup
                        epubFilePath?.let { path ->
                            bookPositionManager.saveScrollPosition("${path}_page_$currentPage", currentScrollY)
                        }

                        // If we have translations, set the flag to reapply them after page load
                        if (pageTranslations.containsKey(currentPage) && pageTranslations[currentPage]?.isNotEmpty() == true) {
                            needToReapplyTranslations = true
                        }

                        // Respect RTL/LTR reading direction for swipe navigation
                        if (isRTLBook) {
                            // RTL: swipe left = previous, swipe right = next
                            if (diffX < 0) {
                                // Swipe left - go to previous page
                                if (currentPage > 0) {
                                    val previousPage = currentPage - 1
                                    currentPage = previousPage
                                    loadPage(previousPage)
                                    return@setOnTouchListener true
                                }
                            } else {
                                // Swipe right - go to next page
                                if (currentPage < totalPages - 1) {
                                    val nextPage = currentPage + 1
                                    currentPage = nextPage
                                    loadPage(nextPage)
                                    return@setOnTouchListener true
                                }
                            }
                        } else {
                            // LTR: swipe right = previous, swipe left = next
                            if (diffX > 0) {
                                // Swipe right - go to previous page
                                if (currentPage > 0) {
                                    val previousPage = currentPage - 1
                                    currentPage = previousPage
                                    loadPage(previousPage)
                                    return@setOnTouchListener true
                                }
                            } else {
                                // Swipe left - go to next page
                                if (currentPage < totalPages - 1) {
                                    val nextPage = currentPage + 1
                                    currentPage = nextPage
                                    loadPage(nextPage)
                                    return@setOnTouchListener true
                                }
                            }
                        }
                    }
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Apply stored translations for the current page
     */
    private fun applyStoredTranslations() {
        try {
            val translations = pageTranslations[currentPage]
            val visibleTranslations = pageVisibleTranslations[currentPage]
            val targetLanguages = pageTranslationTargetLanguages[currentPage]

            if (translations != null && visibleTranslations != null && targetLanguages != null) {
                Log.d(TAG, "Applying ${translations.size} stored translations for page $currentPage")

                translations.forEach { (paragraphId, translatedText) ->
                    val isVisible = visibleTranslations?.contains(paragraphId) ?: false
                    val targetLanguage = targetLanguages?.get(paragraphId) ?: "en"

                    if (isVisible) {
                        // Apply the translation and make it visible using HTML-preserving logic
                        val jsCode = """
                            (function() {
                                try {
                                    var element = document.querySelector('[data-translator-id=\'$paragraphId\']');
                                    if (element) {
                                        // Store original HTML if not already stored
                                        if (!element.hasAttribute('data-original-html')) {
                                            element.setAttribute('data-original-html', element.innerHTML);
                                        }

                                        // Store original text content
                                        element.setAttribute('data-original-text', element.textContent);
                                        element.setAttribute('data-translated-text', `$translatedText`);
                                        element.setAttribute('data-target-language', '$targetLanguage');
                                        element.setAttribute('data-translated', 'true');
                                        element.classList.add('translator-translated');

                                        // Rebuild the in-page toggle state after a relaunch.
                                        if (typeof originalContents !== 'undefined') {
                                            originalContents['$paragraphId'] = element.getAttribute('data-original-html');
                                        }
                                        if (typeof translatedElements !== 'undefined') {
                                            translatedElements['$paragraphId'] = true;
                                        }
                                        if (typeof originalStyles !== 'undefined' && !originalStyles['$paragraphId']) {
                                            originalStyles['$paragraphId'] = {
                                                direction: element.style.direction || '',
                                                textAlign: element.style.textAlign || '',
                                                borderLeft: element.style.borderLeft || '',
                                                borderRight: element.style.borderRight || '',
                                                paddingLeft: element.style.paddingLeft || '',
                                                paddingRight: element.style.paddingRight || ''
                                            };
                                        }

                                        // Preserve HTML structure while replacing text
                                        var tempDiv = document.createElement('div');
                                        tempDiv.innerHTML = element.getAttribute('data-original-html') || element.innerHTML;

                                        // Replace text nodes while preserving other elements (like images)
                                        var textNodes = [];
                                        function collectTextNodes(node) {
                                            if (node.nodeType === Node.TEXT_NODE) {
                                                if (node.textContent.trim().length > 0) {
                                                    textNodes.push(node);
                                                }
                                            } else if (node.nodeType === Node.ELEMENT_NODE) {
                                                for (var i = 0; i < node.childNodes.length; i++) {
                                                    collectTextNodes(node.childNodes[i]);
                                                }
                                            }
                                        }

                                        function replaceTextInNode(node, translatedText) {
                                            collectTextNodes(node);
                                            var originalLength = 0;
                                            for (var i = 0; i < textNodes.length; i++) {
                                                originalLength += textNodes[i].textContent.length;
                                            }
                                            var consumed = 0;
                                            for (var i = 0; i < textNodes.length; i++) {
                                                var start = Math.floor((consumed / originalLength) * translatedText.length);
                                                consumed += textNodes[i].textContent.length;
                                                var end = i === textNodes.length - 1
                                                    ? translatedText.length
                                                    : Math.floor((consumed / originalLength) * translatedText.length);
                                                textNodes[i].textContent = translatedText.substring(start, end);
                                            }
                                        }

                                        replaceTextInNode(tempDiv, `$translatedText`);

                                        // Update element with preserved HTML structure
                                        element.innerHTML = tempDiv.innerHTML;
                                        element.style.border = '2px solid #4CAF50';
                                        element.style.borderRadius = '4px';
                                        element.style.padding = '4px';
                                        element.style.backgroundColor = 'rgba(76, 175, 80, 0.1)';
                                        var attributionId = '$paragraphId-translation-attribution';
                                        var attribution = document.getElementById(attributionId);
                                        if (!attribution) {
                                            attribution = document.createElement('div');
                                            attribution.id = attributionId;
                                            attribution.textContent = 'Translated with Google';
                                            attribution.style.cssText = 'font-size:0.75em;color:#666;margin:4px 0 8px;';
                                            element.parentNode.insertBefore(attribution, element.nextSibling);
                                        }

                                        // Set text direction based on target language
                                        var rtlLangs = ['he','ar','fa','ur'];
                                        if (rtlLangs.indexOf('$targetLanguage') !== -1) {
                                            element.style.direction = 'rtl';
                                            element.style.textAlign = 'right';
                                        } else {
                                            element.style.direction = 'ltr';
                                            element.style.textAlign = 'left';
                                        }

                                        console.log('Applied stored translation for paragraph: $paragraphId');
                                    }
                                } catch (e) {
                                    console.error('Error applying stored translation:', e);
                                }
                            })();
                        """.trimIndent()

                        binding.webView.evaluateJavascript(jsCode, null)
                    }
                }
            } else {
                Log.d(TAG, "No stored translations found for page $currentPage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying stored translations", e)
        }
    }

    /**
     * Share functionality for EPUB files
     */
    private fun shareEpubFile() {
        epubFilePath?.let { filePath ->
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    Toast.makeText(this, "EPUB file not found", Toast.LENGTH_SHORT).show()
                    return
                }

                // Create a content URI using FileProvider for security
                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )

                // Create share intent
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "application/epub+zip"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    putExtra(Intent.EXTRA_SUBJECT, "EPUB Book: ${file.nameWithoutExtension}")
                    putExtra(Intent.EXTRA_TEXT, "Sharing EPUB book: ${file.nameWithoutExtension}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Create chooser to show both "Open with" and "Send to" options
                val chooserIntent = Intent.createChooser(shareIntent, "Share EPUB Book")

                // Add "Open with" option for EPUB reader apps
                val openIntent = Intent().apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(contentUri, "application/epub+zip")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val openChooser = Intent.createChooser(openIntent, "Open with EPUB Reader")
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(openChooser))

                startActivity(chooserIntent)
                Log.d(TAG, "Share intent launched for EPUB file: ${file.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Error sharing EPUB file", e)

                Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(this, "No EPUB file to share", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show paragraph popup menu for single-click actions
     */
    private fun showParagraphPopupMenu(paragraphId: String, paragraphText: String, x: Float, y: Float) {
        try {
            Log.d(TAG, "Showing compact popup menu for paragraph: $paragraphId at position ($x, $y)")

            runOnUiThread {
                // Create compact popup using custom layout
                val popupView = layoutInflater.inflate(R.layout.popup_paragraph_menu, binding.root, false)
                val popupWindow = android.widget.PopupWindow(
                    popupView,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    true
                )

                // Set up click listeners for each menu item
                popupView.findViewById<LinearLayout>(R.id.copyTextButton).setOnClickListener {
                    // Copy text to clipboard
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("EPUB Text", paragraphText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                    popupWindow.dismiss()
                }

                setupTranslationPopupActions(popupView, popupWindow, paragraphId, paragraphText)

                // Configure popup window appearance
                popupWindow.elevation = 12f
                popupWindow.isOutsideTouchable = true
                popupWindow.isFocusable = true

                // Add dismiss listener to reset JavaScript state when popup is closed
                popupWindow.setOnDismissListener {
                    Log.d(TAG, "Popup dismissed, resetting JavaScript state for paragraph: $paragraphId")
                    val resetJs = """
                        (function() {
                            window.processingTap = false;
                            console.log('✅ Reset processingTap flag after popup dismiss');
                        })();
                    """.trimIndent()
                    binding.webView.evaluateJavascript(resetJs, null)
                }

                // Calculate position using WebView's screen location and center horizontally
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val margin = (12 * resources.displayMetrics.density).toInt()

                // Measure popup size more accurately
                popupView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val popupWidth = popupView.measuredWidth
                val popupHeight = popupView.measuredHeight

                // Convert WebView-relative coords to absolute screen coords
                val loc = IntArray(2)
                binding.webView.getLocationOnScreen(loc)
                val centerX = loc[0] + x.toInt()
                val baseY = loc[1] + y.toInt()

                // Start anchored below element (legacy API), center horizontally
                var adjustedX = centerX - (popupWidth / 2)
                var adjustedY = baseY

                // Clamp within screen bounds
                if (adjustedX + popupWidth > screenWidth - margin) adjustedX = screenWidth - popupWidth - margin
                if (adjustedX < margin) adjustedX = margin
                if (adjustedY + popupHeight > screenHeight - margin) adjustedY = screenHeight - popupHeight - margin
                if (adjustedY < margin) adjustedY = margin

                Log.d(TAG, "Showing compact popup at position ($adjustedX, $adjustedY) with size ${popupWidth}x${popupHeight}")

                // Show popup at calculated position
                popupWindow.showAtLocation(binding.webView, android.view.Gravity.NO_GRAVITY, adjustedX, adjustedY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing compact paragraph popup menu", e)
        }
    }
    /**
     * Smarter popup that chooses above/below the tapped paragraph based on available space
     */
    private fun showParagraphPopupMenuSmart(paragraphId: String, paragraphText: String, centerXWeb: Float, rectTopWeb: Float, rectBottomWeb: Float) {
        try {
            Log.d(TAG, "Showing SMART popup for $paragraphId at centerXWeb=$centerXWeb rectTopWeb=$rectTopWeb rectBottomWeb=$rectBottomWeb")
            runOnUiThread {
                val popupView = layoutInflater.inflate(R.layout.popup_paragraph_menu, binding.root, false)
                val popupWindow = android.widget.PopupWindow(
                    popupView,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    true
                )
                popupWindow.elevation = 12f
                popupWindow.isOutsideTouchable = true
                popupWindow.isFocusable = true

                // Measure to decide placement
                popupView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val popupWidth = popupView.measuredWidth
                val popupHeight = popupView.measuredHeight

                val loc = IntArray(2)
                binding.webView.getLocationOnScreen(loc)
                val scale = binding.webView.scale
                val screenCenterX = loc[0] + (centerXWeb * scale).toInt()
                val rectTopScreen = loc[1] + (rectTopWeb * scale).toInt()
                val rectBottomScreen = loc[1] + (rectBottomWeb * scale).toInt()

                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val margin = (12 * resources.displayMetrics.density).toInt()
                val gap = (10 * resources.displayMetrics.density).toInt()

                // Prefer above; fallback below
                val spaceAbove = rectTopScreen - margin
                val fitsAbove = spaceAbove >= popupHeight + gap

                var left = screenCenterX - (popupWidth / 2)
                var top = if (fitsAbove) rectTopScreen - popupHeight - gap else rectBottomScreen + gap

                // Clamp within screen bounds
                if (left + popupWidth > screenWidth - margin) left = screenWidth - popupWidth - margin
                if (left < margin) left = margin
                if (top + popupHeight > screenHeight - margin) top = screenHeight - popupHeight - margin
                if (top < margin) top = margin

                // Actions
                popupView.findViewById<LinearLayout>(R.id.copyTextButton).setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("EPUB Text", paragraphText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
                    popupWindow.dismiss()
                }
                setupTranslationPopupActions(popupView, popupWindow, paragraphId, paragraphText)

                Log.d(TAG, "SMART popup at ($left,$top) size ${popupWidth}x${popupHeight} fitsAbove=$fitsAbove")
                popupWindow.showAtLocation(binding.webView, android.view.Gravity.NO_GRAVITY, left, top)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing SMART paragraph popup menu", e)
        }
    }


    /**
     * Handle double-click translation for paragraphs
     */
    private fun handleDoubleClickTranslation(paragraphId: String, paragraphText: String) {
        synchronized(translationLock) {
            try {
                Log.d(TAG, "🔄 HANDLING DOUBLE-CLICK TRANSLATION: paragraphId=$paragraphId")
                Log.d(TAG, "🔄 Current page: $currentPage")

                if (paragraphText.trim().isEmpty()) {
                    Log.d(TAG, "❌ Empty paragraph text, skipping translation")
                    return
                }

                // Check if translation is already in progress for this paragraph
                if (pendingTranslations.contains(paragraphId)) {
                    Log.d(TAG, "⏳ Translation already in progress for paragraph: $paragraphId")
                    return
                }

                // Check if this paragraph is already translated
                val currentTranslations = pageTranslations[currentPage]
                val currentVisibleTranslations = pageVisibleTranslations[currentPage]
                val isCurrentlyTranslated = currentVisibleTranslations?.contains(paragraphId) == true
                val currentTranslationMethod = translationManager.getCurrentMethod()

                // Check if the paragraph was translated with the same method and language
                val currentTargetLanguages = pageTranslationTargetLanguages[currentPage]
                val storedTargetLanguage = currentTargetLanguages?.get(paragraphId)
                val currentTargetLanguage = translationManager.getTargetLanguage().code

                val currentTranslationMethods = pageTranslationMethods[currentPage]
                val storedTranslationMethod = currentTranslationMethods?.get(paragraphId)

                val sameLanguage = storedTargetLanguage == currentTargetLanguage
                val sameMethod = storedTranslationMethod == currentTranslationMethod
                val sameLanguageAndMethod = sameLanguage && sameMethod

                Log.d(TAG, "🔍 Translation state check: isCurrentlyTranslated=$isCurrentlyTranslated")
                Log.d(TAG, "🔍 Current method: $currentTranslationMethod, Stored method: $storedTranslationMethod")
                Log.d(TAG, "🔍 Stored target language: $storedTargetLanguage, Current: $currentTargetLanguage")
                Log.d(TAG, "🔍 Same language: $sameLanguage, Same method: $sameMethod")
                Log.d(TAG, "🔍 Same language and method: $sameLanguageAndMethod")
                Log.d(TAG, "🔍 Current translations count: ${currentTranslations?.size ?: 0}")
                Log.d(TAG, "🔍 Visible translations count: ${currentVisibleTranslations?.size ?: 0}")
                Log.d(TAG, "🔍 Pending translations: ${pendingTranslations.size}")

                // Default API uses double-tap as a show/hide toggle. G/Y methods use a
                // repeated double-tap as a fresh external translation attempt.
                if (currentTranslationMethod == TranslationMethod.DEFAULT &&
                    storedTranslationMethod == TranslationMethod.DEFAULT &&
                    sameLanguage && isCurrentlyTranslated
                ) {
                    Log.d(TAG, "🔙 Restoring original paragraph: $paragraphId")
                    toggleTranslationVisibility(paragraphId, false)
                    return
                }

                if (isCurrentlyTranslated) {
                    Log.d(TAG, "🔄 Re-translating paragraph: $paragraphId")
                    currentVisibleTranslations?.remove(paragraphId)
                    currentTranslations?.remove(paragraphId)
                    currentTargetLanguages?.remove(paragraphId)
                    currentTranslationMethods?.remove(paragraphId)
                }

                val consentPrefs = getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
                if (!consentPrefs.getBoolean("rights_consent_accepted", false)) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.translation_consent_title)
                        .setMessage(R.string.translation_consent_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.translation_consent_accept) { _, _ ->
                            consentPrefs.edit().putBoolean("rights_consent_accepted", true).apply()
                            handleDoubleClickTranslation(paragraphId, paragraphText)
                        }
                        .show()
                    return
                }

                pendingTranslations.add(paragraphId)
                if (!consumeTranslationCredit()) {
                    pendingTranslations.remove(paragraphId)
                    return
                }
                Log.d(TAG, "🌐 Starting translation for paragraph: $paragraphId (marked as pending)")
                performTranslation(paragraphId, paragraphText)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error handling double-click translation", e)
                // Remove from pending if error occurred
                pendingTranslations.remove(paragraphId)
            }
        }
    }

    /**
     * Toggle translation visibility for a paragraph
     */
    private fun toggleTranslationVisibility(paragraphId: String, showTranslation: Boolean) {
        try {
            val visibleTranslations = pageVisibleTranslations.getOrPut(currentPage) { mutableSetOf() }

            if (showTranslation) {
                visibleTranslations.add(paragraphId)
                Log.d(TAG, "Showing translation for paragraph: $paragraphId")
            } else {
                visibleTranslations.remove(paragraphId)
                Log.d(TAG, "Hiding translation for paragraph: $paragraphId")
            }

            // Apply the visibility change via JavaScript
            val jsCode = if (showTranslation) {
                val translations = pageTranslations[currentPage]
                val targetLanguages = pageTranslationTargetLanguages[currentPage]
                val translatedText = translations?.get(paragraphId) ?: ""
                val targetLanguage = targetLanguages?.get(paragraphId) ?: "en"

                """
                (function() {
                    try {
                        var element = document.querySelector('[data-translator-id=\'$paragraphId\']');
                        if (element) {
                            var elementId = '$paragraphId';

                            // Capture the original HTML only once, before the first translation.
                            // Reapplying a stored translation must never replace this snapshot.
                            var originalHtml = element.getAttribute('data-original-html');
                            if (!originalHtml) {
                                originalHtml = element.innerHTML;
                                element.setAttribute('data-original-html', originalHtml);
                                element.setAttribute('data-original-text', element.textContent);
                            }
                            element.setAttribute('data-translated-text', `$translatedText`);
                            element.setAttribute('data-target-language', '$targetLanguage');

                            // CRITICAL: Set data-translated attribute for JavaScript state tracking
                            element.setAttribute('data-translated', 'true');
                            element.classList.add('translator-translated');

                            // CRITICAL: Update JavaScript state objects for proper toggle functionality
                            if (typeof originalContents !== 'undefined') {
                                originalContents[elementId] = originalHtml;
                            }
                            if (typeof translatedElements !== 'undefined') {
                                translatedElements[elementId] = true;
                            }
                            if (typeof originalStyles !== 'undefined') {
                                originalStyles[elementId] = {
                                    direction: element.style.direction || '',
                                    textAlign: element.style.textAlign || '',
                                    borderLeft: element.style.borderLeft || '',
                                    borderRight: element.style.borderRight || '',
                                    paddingLeft: element.style.paddingLeft || '',
                                    paddingRight: element.style.paddingRight || ''
                                };
                            }

                            // Replace text nodes while preserving inline formatting and media.
                            var textNodes = [];
                            function collectTextNodes(node) {
                                if (node.nodeType === Node.TEXT_NODE) {
                                    if (node.textContent.trim().length > 0) {
                                        textNodes.push(node);
                                    }
                                } else if (node.nodeType === Node.ELEMENT_NODE) {
                                    for (var i = 0; i < node.childNodes.length; i++) {
                                        collectTextNodes(node.childNodes[i]);
                                    }
                                }
                            }

                            collectTextNodes(element);
                            var originalLength = 0;
                            for (var i = 0; i < textNodes.length; i++) {
                                originalLength += textNodes[i].textContent.length;
                            }
                            if (originalLength > 0) {
                                var consumed = 0;
                                for (var i = 0; i < textNodes.length; i++) {
                                    var start = Math.floor((consumed / originalLength) * `$translatedText`.length);
                                    consumed += textNodes[i].textContent.length;
                                    var end = i === textNodes.length - 1
                                        ? `$translatedText`.length
                                        : Math.floor((consumed / originalLength) * `$translatedText`.length);
                                    textNodes[i].textContent = `$translatedText`.substring(start, end);
                                }
                            } else {
                                element.textContent = `$translatedText`;
                            }
                            element.style.border = '2px solid #4CAF50';
                            element.style.borderRadius = '4px';
                            element.style.padding = '4px';
                            element.style.backgroundColor = 'rgba(76, 175, 80, 0.1)';
                            var attributionId = elementId + '-translation-attribution';
                            var attribution = document.getElementById(attributionId);
                            if (!attribution) {
                                attribution = document.createElement('div');
                                attribution.id = attributionId;
                                attribution.textContent = 'Translated with Google';
                                attribution.style.cssText = 'font-size:0.75em;color:#666;margin:4px 0 8px;';
                                element.parentNode.insertBefore(attribution, element.nextSibling);
                            }

                            // Set text direction based on target language
                            var rtlLangs = ['he','ar','fa','ur'];
                            if (rtlLangs.indexOf('$targetLanguage') !== -1) {
                                element.style.direction = 'rtl';
                                element.style.textAlign = 'right';
                            } else {
                                element.style.direction = 'ltr';
                                element.style.textAlign = 'left';
                            }

                            console.log('✅ Translation applied and state synced for: ' + elementId);
                        }
                    } catch (e) {
                        console.error('Error showing translation:', e);
                    }
                })();
                """.trimIndent()
            } else {
                """
                (function() {
                    try {
                        var element = document.querySelector('[data-translator-id=\'$paragraphId\']');
                        if (element && element.hasAttribute('data-original-html')) {
                            // Restore original HTML content (preserving images)
                            element.innerHTML = element.getAttribute('data-original-html');
                            element.style.border = '';
                            element.style.borderRadius = '';
                            element.style.padding = '';
                            element.style.backgroundColor = '';
                            element.style.direction = '';
                            element.style.textAlign = '';
                            element.removeAttribute('data-translated');
                            element.classList.remove('translator-translated');
                            if (typeof translatedElements !== 'undefined') {
                                delete translatedElements['$paragraphId'];
                            }
                            element.removeAttribute('data-original-html');
                            element.removeAttribute('data-original-text');
                            element.removeAttribute('data-translated-text');
                            element.removeAttribute('data-target-language');
                            var attribution = document.getElementById('$paragraphId-translation-attribution');
                            if (attribution) attribution.remove();
                        } else if (element && element.hasAttribute('data-original-text')) {
                            // Fallback to text-only restoration for backward compatibility
                            element.textContent = element.getAttribute('data-original-text');
                            element.style.border = '';
                            element.style.borderRadius = '';
                            element.style.padding = '';
                            element.style.backgroundColor = '';
                            element.style.direction = '';
                            element.style.textAlign = '';
                            element.removeAttribute('data-translated');
                            element.classList.remove('translator-translated');
                            if (typeof translatedElements !== 'undefined') {
                                delete translatedElements['$paragraphId'];
                            }
                            element.removeAttribute('data-original-text');
                            element.removeAttribute('data-translated-text');
                            element.removeAttribute('data-target-language');
                        }
                    } catch (e) {
                        console.error('Error hiding translation:', e);
                    }
                })();
                """.trimIndent()
            }

            binding.webView.evaluateJavascript(jsCode, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling translation visibility", e)
        }
    }

    /**
     * Perform translation for a paragraph
     */
    private fun performTranslation(paragraphId: String, paragraphText: String) {
        try {
            Log.d(TAG, "Performing translation for paragraph: $paragraphId")

            performDefaultTranslation(paragraphId, paragraphText)
        } catch (e: Exception) {
            Log.e(TAG, "Error in performTranslation", e)
            resetElementState(paragraphId)
            Toast.makeText(this, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Perform default API-based translation
     */
    private fun performDefaultTranslation(paragraphId: String, paragraphText: String) {
        try {
            // Get current target language from translation manager
            val targetLanguage = translationManager.getTargetLanguage().code

            // Do not block when detection claims same-language; proceed to force translation to target
            val detectedSource = translationManager.detectLanguage(paragraphText)
            if (detectedSource == targetLanguage) {
                Log.w(TAG, "Detection matched target ($targetLanguage), but proceeding to translate anyway to honor user target")
            }

            // Show loading indicator
            showLoadingState(paragraphId)

            // Perform translation using the translation manager
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    Log.d(TAG, "🌐 Calling translation manager with text: ${paragraphText.take(30)}...")
                    Log.d(TAG, "🌐 Target language: $targetLanguage")

                    val result = translationManager.translateText(paragraphText)
                    Log.d(TAG, "🌐 Translation manager returned result")

                    result.fold(
                        onSuccess = { translatedText ->
                            synchronized(translationLock) {
                                try {
                                    Log.d(TAG, "✅ Translation SUCCESS: ${translatedText.take(30)}...")
                                    Log.d(TAG, "✅ Original != Translated: ${translatedText != paragraphText}")

                                    if (translatedText.isNotEmpty() && translatedText != paragraphText) {
                                        // Store the translation
                                        val translations = pageTranslations.getOrPut(currentPage) { mutableMapOf() }
                                        val targetLanguages = pageTranslationTargetLanguages.getOrPut(currentPage) { mutableMapOf() }
                                        val translationMethods = pageTranslationMethods.getOrPut(currentPage) { mutableMapOf() }

                                        translations[paragraphId] = translatedText
                                        targetLanguages[paragraphId] = targetLanguage
                                        translationMethods[paragraphId] = TranslationMethod.DEFAULT

                                        Log.d(TAG, "💾 Stored translation for paragraph: $paragraphId")
                                        Log.d(TAG, "💾 Total translations for page $currentPage: ${translations.size}")

                                        // Show the translation
                                        toggleTranslationVisibility(paragraphId, true)

                                        Log.d(TAG, "✅ Translation completed for paragraph: $paragraphId")
                                        Toast.makeText(this@EpubReaderActivity, "Translation completed", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // Translation failed, remove loading state
                                        Log.w(TAG, "⚠️ Translation result empty or same as original")
                                        refundTranslationCredit()
                                        resetElementState(paragraphId)
                                        Toast.makeText(this@EpubReaderActivity, "Translation failed - empty result", Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    // Always remove from pending translations
                                    pendingTranslations.remove(paragraphId)
                                    Log.d(TAG, "🔓 Removed paragraph from pending: $paragraphId")
                                }
                            }
                        },
                        onFailure = { error ->
                            synchronized(translationLock) {
                                try {
                                    // Translation failed, remove loading state
                                    Log.e(TAG, "❌ Translation FAILED: ${error.message}", error)
                                    refundTranslationCredit()
                                    resetElementState(paragraphId)
                                    Log.e(TAG, "Translation error: ${error.message}")
                                    if (error is ModelNotDownloadedException) {
                                        showModelDownloadPrompt()
                                    } else {
                                        Toast.makeText(this@EpubReaderActivity, "Translation error: ${error.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    // Always remove from pending translations
                                    pendingTranslations.remove(paragraphId)
                                    Log.d(TAG, "🔓 Removed paragraph from pending (error): $paragraphId")
                                }
                            }
                        }
                    )
                } catch (e: Exception) {
                    synchronized(translationLock) {
                        try {
                            Log.e(TAG, "Error during translation", e)
                            refundTranslationCredit()
                            resetElementState(paragraphId)
                            Toast.makeText(this@EpubReaderActivity, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            // Always remove from pending translations
                            pendingTranslations.remove(paragraphId)
                            Log.d(TAG, "🔓 Removed paragraph from pending (exception): $paragraphId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error performing default translation", e)
            refundTranslationCredit()
            resetElementState(paragraphId)
            Toast.makeText(this, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reset element visual state (remove loading indicators)
     */
    private fun resetElementState(paragraphId: String) {
        pendingTranslations.remove(paragraphId)
        val resetJsCode = """
            (function() {
                try {
                    var element = document.querySelector('[data-translator-id=\'$paragraphId\']');
                    if (element) {
                        element.style.border = '';
                        element.style.borderRadius = '';
                        element.style.padding = '';
                        element.style.backgroundColor = '';
                    }
                } catch (e) {
                    console.error('Error resetting element state:', e);
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(resetJsCode, null)
    }

    private fun showModelDownloadPrompt() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.offline_model_not_downloaded_title)
            .setMessage(R.string.offline_model_not_downloaded)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .show()
    }

    /**
     * Set up the translation method dropdown in the toolbar
     */
    private fun setupTranslationMethodDropdown() {
        val container = binding.translationMethodContainer
        val textView = binding.translationMethodText

        // Set initial method
        val currentMethod = translationManager.getCurrentMethod()
        textView.text = currentMethod.displayChar

        // Set up click listener for dropdown
        container.setOnClickListener {
            showTranslationMethodMenu()
        }
    }

    /**
     * Show the translation method selection menu
     */
    private fun showTranslationMethodMenu() {
        val popup = PopupMenu(this, binding.translationMethodContainer)
        popup.menuInflater.inflate(R.menu.translation_method_menu, popup.menu)

        // Set current selection
        val currentMethod = translationManager.getCurrentMethod()
        popup.menu.findItem(R.id.translation_method_default)?.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.translation_method_default -> {
                    setTranslationMethod(TranslationMethod.DEFAULT)
                    true
                }
                else -> false
            }
        }

        popup.show()
    }

    /**
     * Set the translation method and update UI
     */
    private fun setTranslationMethod(method: TranslationMethod) {
        translationManager.setTranslationMethod(method)
        binding.translationMethodText.text = method.displayChar

        val methodName = getString(method.displayNameResId)
        Toast.makeText(this, getString(R.string.translation_method_selected, methodName), Toast.LENGTH_SHORT).show()

        Log.d(TAG, "Translation method changed to: $method")
    }

    /**
     * Show loading state for a paragraph
     */
    private fun showLoadingState(paragraphId: String) {
        val loadingJsCode = """
            (function() {
                try {
                    var element = document.querySelector('[data-translator-id=\'$paragraphId\']');
                    if (element) {
                        element.style.border = '2px solid #FF9800';
                        element.style.borderRadius = '4px';
                        element.style.padding = '4px';
                        element.style.backgroundColor = 'rgba(255, 152, 0, 0.1)';
                    }
                } catch (e) {
                    console.error('Error showing loading state:', e);
                }
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(loadingJsCode, null)
    }
    
    private fun openExternalTranslationApp(paragraphText: String, packageName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, paragraphText)
            setPackage(packageName)
        }
        try {
            startActivity(shareIntent)
        } catch (missingAppError: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            } catch (playStoreError: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                Log.w(TAG, "External translation app unavailable: $packageName", missingAppError)
            }
        }
    }

    /**
     * JavaScript interface for WebView communication
     */
    inner class JavaScriptInterface {
        @android.webkit.JavascriptInterface
        fun onLinkClick(href: String) {
            runOnUiThread {
                if (href.isNotEmpty()) {
                    handleBookLink(href)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onParagraphClick(paragraphId: String, paragraphText: String, x: Float, y: Float) {
            runOnUiThread {
                try {
                    Log.d(TAG, "Paragraph clicked: $paragraphId")
                    showParagraphPopupMenu(paragraphId, paragraphText, x, y)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling paragraph click", e)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onParagraphDoubleClick(paragraphId: String, paragraphText: String) {
            runOnUiThread {
                try {
                    Log.d(TAG, "🔥 DOUBLE-CLICK DETECTED: paragraphId=$paragraphId, textLength=${paragraphText.length}")
                    Log.d(TAG, "🔥 DOUBLE-CLICK TEXT: ${paragraphText.take(50)}...")

                    // Enhanced debugging
                    Log.d(TAG, "🔍 Translation Manager initialized: ${::translationManager.isInitialized}")
                    Log.d(TAG, "🔍 Current page: $currentPage")
                    Log.d(TAG, "🔍 Page translations size: ${pageTranslations.size}")
                    Log.d(TAG, "🔍 Page visible translations size: ${pageVisibleTranslations.size}")

                    handleDoubleClickTranslation(paragraphId, paragraphText)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling paragraph double-click", e)
                    // Show user-visible error
                    Toast.makeText(this@EpubReaderActivity, "Double-tap translation error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onParagraphTripleTapped(paragraphText: String, paragraphId: String) {
            runOnUiThread {
                try {
                    Log.d(TAG, "Long-press translation dialog requested: $paragraphId")
                    showTranslationDialog(paragraphId, paragraphText)
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing long-press translation dialog", e)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onShowParagraphPopup(paragraphText: String, paragraphId: String, x: Float, y: Float) {
            runOnUiThread {
                try {
                    Log.d(TAG, "Paragraph single-clicked: $paragraphId")
                    showParagraphPopupMenu(paragraphId, paragraphText, x, y)
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing paragraph popup", e)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onToggleTranslation(originalText: String, paragraphId: String) {
            runOnUiThread {
                try {
                    Log.d(TAG, "🔄 Toggle translation called from JavaScript: paragraphId=$paragraphId")
                    Log.d(TAG, "🔄 Original text: ${originalText.take(50)}...")

                    // Update Android-side state to mark paragraph as not translated
                    synchronized(translationLock) {
                        val currentVisibleTranslations = pageVisibleTranslations[currentPage]
                        val currentTranslations = pageTranslations[currentPage]
                        val currentTargetLanguages = pageTranslationTargetLanguages[currentPage]
                        val currentTranslationMethods = pageTranslationMethods[currentPage]

                        // Default API keeps the stored translation so the following
                        // double-tap can restore the original paragraph. G/Y retries
                        // clear visibility because they start a new external attempt.
                        val storedMethod = currentTranslationMethods?.get(paragraphId)
                        if (storedMethod != TranslationMethod.DEFAULT) {
                            currentVisibleTranslations?.remove(paragraphId)
                        }

                        // Keep the translation data but mark as not visible
                        // This allows re-translation without losing the stored translation

                        Log.d(TAG, "✅ Paragraph marked as not visible: $paragraphId")
                        Log.d(TAG, "📊 Visible translations count: ${currentVisibleTranslations?.size ?: 0}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling toggle translation", e)
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onPageReady() {
            runOnUiThread {
                try {
                    Log.d(TAG, "Page ready notification received")
                    // Apply stored translations after page is ready
                    Handler(Looper.getMainLooper()).postDelayed({
                        applyStoredTranslations()
                    }, 100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling page ready", e)
                }
            }
        }
    }
}
