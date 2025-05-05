package com.example.epubtranslator

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import com.example.epubtranslator.data.BookPositionManager
import com.example.epubtranslator.databinding.ActivityEpubReaderBinding
import com.example.epubtranslator.translation.TranslationDialog
import com.example.epubtranslator.translation.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class EpubReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EPUB_FILE_PATH = "epub_file_path"
        private const val JS_INTERFACE_NAME = "AndroidTranslator"
        private const val TAG = "EpubReaderActivity"
    }

    private lateinit var binding: ActivityEpubReaderBinding
    private lateinit var translationManager: TranslationManager
    private lateinit var bookPositionManager: BookPositionManager
    private var epubFilePath: String? = null
    private var currentPage = 0
    private var totalPages = 0
    private var htmlFiles = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpubReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get the EPUB file path from intent
        epubFilePath = intent.getStringExtra(EXTRA_EPUB_FILE_PATH)
        if (epubFilePath == null) {
            finish()
            return
        }

        // Initialize managers
        translationManager = TranslationManager(this)
        bookPositionManager = BookPositionManager(this)

        // Set up WebView
        setupWebView()

        // Set up gesture detector for swipe navigation
        setupGestureDetector()

        // Navigation buttons have been removed

        // Inline translation has been removed

        // Load the EPUB file
        loadEpubFile()
    }

    override fun onPause() {
        super.onPause()
        // Save position when activity is paused
        saveCurrentPosition()
    }

    override fun onStop() {
        super.onStop()
        // Save position when activity is stopped
        saveCurrentPosition()
    }

    private fun saveCurrentPosition() {
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

                if (htmlFiles.isNotEmpty()) {
                    // Get the last saved position
                    epubFilePath?.let { path ->
                        val savedPosition = bookPositionManager.getPosition(path)
                        currentPage = if (savedPosition >= 0 && savedPosition < totalPages) {
                            savedPosition
                        } else {
                            0
                        }
                    }

                    // Preload all saved scroll positions
                    preloadScrollPositions()

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
        val imageEntries = mutableMapOf<String, String>() // Store image paths instead of byte arrays
        val imageCache = File(cacheDir, "epub_images")

        // Always clear the image cache directory to ensure no cache is stored between sessions
        if (imageCache.exists()) {
            imageCache.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Cleared image cache directory to ensure no cache is stored")
        } else {
            imageCache.mkdirs()
        }

        // We don't use HTML cache files anymore to avoid storing cache between sessions

        try {
            val zipFile = ZipFile(epubPath)
            val allEntries = zipFile.entries().toList() // Convert to list to avoid multiple iterations

            // First, quickly identify all HTML files and images
            val htmlEntries = mutableListOf<ZipEntry>()
            val imageEntriesMap = mutableMapOf<String, ZipEntry>()

            for (entry in allEntries) {
                if (entry.isDirectory) continue

                val entryName = entry.name.lowercase()
                when {
                    entryName.endsWith(".html") || entryName.endsWith(".xhtml") -> {
                        htmlEntries.add(entry)
                    }
                    entryName.endsWith(".jpg") || entryName.endsWith(".jpeg") ||
                    entryName.endsWith(".png") || entryName.endsWith(".gif") ||
                    entryName.endsWith(".svg") -> {
                        imageEntriesMap[entry.name] = entry
                    }
                }
            }

            // Process only the images that are actually referenced in HTML files
            // This avoids extracting unused images
            val referencedImages = mutableSetOf<String>()

            // First pass: scan HTML files for image references without fully processing them
            for (entry in htmlEntries) {
                try {
                    val content = readZipEntry(zipFile, entry)
                    val imgPattern = "<img[^>]+src=\"([^\"]+)\"[^>]*>".toRegex()
                    val matches = imgPattern.findAll(content)

                    for (match in matches) {
                        val src = match.groupValues[1]
                        val absolutePath = resolveRelativePath(src, entry.name)
                        referencedImages.add(absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning HTML for images: ${entry.name}", e)
                }
            }

            // Process only the referenced images
            for (imagePath in referencedImages) {
                val entry = imageEntriesMap[imagePath] ?: continue

                // Create a safe filename for the image
                val safeFileName = entry.name.replace("/", "_").replace("\\", "_")
                val imageFile = File(imageCache, safeFileName)

                // Only extract if the image doesn't already exist in cache
                if (!imageFile.exists()) {
                    try {
                        // Extract and save the image to cache
                        zipFile.getInputStream(entry).use { input ->
                            val output = imageFile.outputStream()
                            input.copyTo(output)
                            output.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting image: ${entry.name}", e)
                    }
                }

                // Store the image path for reference
                imageEntries[entry.name] = imageFile.absolutePath
            }

            // Process HTML files without caching
            for (entry in htmlEntries) {
                try {
                    var content = readZipEntry(zipFile, entry)

                    // Process the HTML to fix image references
                    content = processHtmlContent(content, entry.name, imageCache, imageEntries)

                    htmlContents.add(content)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing HTML: ${entry.name}", e)
                }
            }

            zipFile.close()

            // We're not saving HTML cache anymore to avoid storing cache between sessions

            // Log the number of images found
            Log.d(TAG, "Found ${imageEntries.size} images in EPUB")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting EPUB", e)
            e.printStackTrace()
        }

        return htmlContents
    }

    /**
     * Process HTML content to fix image references
     */
    private fun processHtmlContent(html: String, entryPath: String, imageCache: File, imageEntries: Map<String, String>): String {
        try {
            val doc = Jsoup.parse(html)

            // Fix image sources
            val images = doc.select("img")
            for (img in images) {
                val src = img.attr("src")
                if (src.isNotEmpty()) {
                    // Handle relative paths
                    val absolutePath = resolveRelativePath(src, entryPath)

                    // Check if we have this image in our entries
                    val imagePath = imageEntries[absolutePath]
                    if (imagePath != null) {
                        // Use file:// protocol for local files
                        img.attr("src", "file://$imagePath")

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

                            // Add loading attributes for better image handling
                            img.attr("loading", "lazy")
                            img.attr("decoding", "async")
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
     * Resolve a relative path against a base path
     */
    private fun resolveRelativePath(relativePath: String, basePath: String): String {
        // If the path is already absolute, return it
        if (relativePath.startsWith("/") || relativePath.contains("://")) {
            return relativePath
        }

        // Get the directory part of the base path
        val baseDir = basePath.substringBeforeLast("/", "")

        // Handle parent directory references (../)
        var path = relativePath
        var dir = baseDir

        while (path.startsWith("../")) {
            path = path.substring(3)
            dir = dir.substringBeforeLast("/", "")
        }

        // Handle current directory references (./)
        if (path.startsWith("./")) {
            path = path.substring(2)
        }

        // Combine the directory and the relative path
        return if (dir.isEmpty()) path else "$dir/$path"
    }

    private fun readZipEntry(zipFile: ZipFile, entry: ZipEntry): String {
        val inputStream = zipFile.getInputStream(entry)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            stringBuilder.append(line).append("\n")
        }

        reader.close()
        return stringBuilder.toString()
    }

    private fun loadPage(pageIndex: Int) {
        if (pageIndex < 0 || pageIndex >= htmlFiles.size) {
            return
        }

        val htmlContent = htmlFiles[pageIndex]

        // Clean the HTML content using JSoup
        val cleanHtml = cleanHtml(htmlContent)

        // Show loading indicator for better user experience
        binding.progressBar.visibility = View.VISIBLE

        // Apply a fade-out effect to reduce the perception of glitches
        binding.webView.alpha = 0.3f

        // Get the target scroll position before loading the page
        val targetScrollY = pageScrollPositions[pageIndex] ?: 0
        Log.d(TAG, "Target scroll position for page $pageIndex: $targetScrollY")

        // Load the HTML content into the WebView
        binding.webView.loadDataWithBaseURL(null, cleanHtml, "text/html", "UTF-8", null)

        // Update page number
        binding.pageNumberTextView.text = "${pageIndex + 1} / $totalPages"
        currentPage = pageIndex

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
                p:hover, div:hover, span:hover, h1:hover, h2:hover, h3:hover, h4:hover, h5:hover, h6:hover, li:hover {
                    background-color: rgba(0, 0, 0, 0.05);
                }
                p:active, div:active, span:active, h1:active, h2:active, h3:active, h4:active, h5:active, h6:active, li:active {
                    background-color: rgba(0, 0, 255, 0.05);
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
                        p:hover, div:hover, span:hover, h1:hover, h2:hover, h3:hover, h4:hover, h5:hover, h6:hover, li:hover {
                            background-color: rgba(0, 0, 0, 0.05);
                        }
                        p:active, div:active, span:active, h1:active, h2:active, h3:active, h4:active, h5:active, h6:active, li:active {
                            background-color: rgba(0, 0, 255, 0.05);
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

        // Set WebViewClient to inject our JavaScript after page load
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Hide the progress bar
                binding.progressBar.visibility = View.GONE

                // Apply a smooth fade-in effect
                binding.webView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .start()

                // Get the target scroll position
                val targetScrollY = pageScrollPositions[currentPage] ?: run {
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
                }

                // Check if we need to reapply translations
                if (needToReapplyTranslations || (pageTranslations.containsKey(currentPage) && pageTranslations[currentPage]?.isNotEmpty() == true)) {
                    Log.d(TAG, "Need to reapply translations for page $currentPage")
                    applyStoredTranslations()
                }

                // Inject JavaScript to handle text selection, double-click, long-press, and inline translation
                val jsCode = """
                    // Track clicks and text selection
                    var lastClickTime = 0;
                    var lastClickElement = null;
                    var clickCount = 0;
                    var translatedElements = {};
                    var originalContents = {};
                    var originalStyles = {};
                    var longPressTimer = null;
                    var longPressElement = null;
                    var selectionTimer = null;
                    var highlightedElements = {};
                    var currentSelection = null;

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
                            // Add visual indicator
                            element.style.backgroundColor = 'rgba(144, 238, 144, 0.2)';

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

                                // Mark as not translated
                                window.markElementAsTranslated(elementId, false);
                                console.log('Restored original content for element: ' + elementId);
                                return true;
                            } else {
                                console.error('Original content not found for element: ' + elementId);
                                return false;
                            }
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

                        // Add a mutation observer to handle dynamically added content
                        var observer = new MutationObserver(function(mutations) {
                            processTextElements();
                            // Also check for new translated elements
                            restoreTranslatedState();
                        });

                        observer.observe(document.body, { childList: true, subtree: true });

                        console.log('Translation system initialized');
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

                        // First, prioritize proper paragraph elements
                        var paragraphs = document.querySelectorAll('p, div.paragraph, div.text, article > div, section > div');
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

                                // Add the paragraph element
                                textElements.push(element);
                                processedElements.add(element);
                                console.log('Added paragraph element: ' + text.substring(0, 30) + '...');
                            }
                        }

                        // Now process other text-containing elements
                        var allElements = document.querySelectorAll('div, section, article, span, h1, h2, h3, h4, h5, h6, li');

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
                        var elementId = 'translator-' + contentHash + '-' + elementPath.length;
                        element.setAttribute('data-translator-id', elementId);
                        element.setAttribute('data-content-hash', contentHash);

                        // Log the element ID for debugging
                        console.log('Assigned ID: ' + elementId + ' to element with text: ' + elementText.substring(0, 30) + '...');

                        // Add multi-tap handler for both click and touch events
                        function handleTap(event) {
                            var now = new Date().getTime();
                            var timeSince = now - lastClickTime;
                            var thisElement = this;

                            console.log('Tap detected, time since last: ' + timeSince + 'ms, clickCount: ' + clickCount);

                            // Check if this is a tap on the same element (within 500ms)
                            if (timeSince < 500 && thisElement === lastClickElement) {
                                clickCount++;
                                console.log('Incremented clickCount to: ' + clickCount);

                                // Clear any existing timeout
                                if (window.tapTimeout) {
                                    clearTimeout(window.tapTimeout);
                                }

                                // Set a timeout to handle the double-tap after a short delay
                                window.tapTimeout = setTimeout(function() {
                                    if (clickCount === 2) {
                                        console.log('Double-tap detected');
                                        // Double-tap: translate paragraph inline
                                        handleInlineTranslation(thisElement, event);
                                    }
                                    // We've removed triple-tap handling as we now use long-press for translation dialog

                                    // Reset click count
                                    clickCount = 0;
                                }, 300);
                            } else {
                                // Reset click count for new element
                                clickCount = 1;
                                console.log('Reset clickCount to 1 (new element or timeout)');

                                // Clear any existing timeout
                                if (window.tapTimeout) {
                                    clearTimeout(window.tapTimeout);
                                }
                            }

                            // Update tracking variables
                            lastClickTime = now;
                            lastClickElement = thisElement;
                        }

                        // Add the handler to both click and touchend events
                        element.addEventListener('click', handleTap);
                        element.addEventListener('touchend', function(event) {
                            // Prevent default only for touchend to avoid interfering with other interactions
                            event.preventDefault();
                            handleTap.call(this, event);
                        });



                        // Add long-press handlers
                        element.addEventListener('touchstart', function(event) {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                            }

                            var self = this;
                            longPressElement = this;

                            longPressTimer = setTimeout(function() {
                                if (self === longPressElement) {
                                    // Get the element ID
                                    var elementId = self.getAttribute('data-translator-id');
                                    var paragraphText = self.textContent.trim();

                                    if (paragraphText.length > 0 && elementId) {
                                        // Prevent default behavior and stop propagation
                                        event.preventDefault();
                                        event.stopPropagation();

                                        console.log('Long press detected, showing translation dialog for: ' + paragraphText.substring(0, 30) + '...');

                                        // Check if this element is already translated
                                        if (translatedElements[elementId]) {
                                            // If already translated, use the original text for the dialog
                                            var originalText = '';
                                            if (originalContents[elementId]) {
                                                // Create a temporary element to extract text from HTML
                                                var tempDiv = document.createElement('div');
                                                tempDiv.innerHTML = originalContents[elementId];
                                                originalText = tempDiv.textContent.trim();

                                                // Send the original text to Android for dialog translation
                                                window.AndroidTranslator.onParagraphTripleTapped(originalText, elementId);
                                            }
                                        } else {
                                            // Send the paragraph text to Android for dialog translation
                                            window.AndroidTranslator.onParagraphTripleTapped(paragraphText, elementId);
                                        }
                                    }
                                }
                            }, 800); // 800ms for long press
                        });

                        element.addEventListener('touchend', function() {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                            }
                            longPressElement = null;
                        });

                        element.addEventListener('touchmove', function() {
                            if (longPressTimer) {
                                clearTimeout(longPressTimer);
                            }
                            longPressElement = null;
                        });
                    }

                    // Handle inline translation of an element (double-tap)
                    function handleInlineTranslation(element, event) {
                        var elementId = element.getAttribute('data-translator-id');

                        if (!elementId) {
                            console.error('Element has no translator ID');
                            return;
                        }

                        // Prevent default behavior and stop propagation
                        event.preventDefault();
                        event.stopPropagation();

                        // SIMPLIFIED APPROACH: Check if element has the data-translated attribute
                        if (element.hasAttribute('data-translated')) {
                            console.log('Element is already translated, toggling back to original');

                            // DIRECT APPROACH: Restore original content immediately
                            if (originalContents[elementId]) {
                                // Restore original content
                                element.innerHTML = originalContents[elementId];

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

                                // Update state tracking
                                delete translatedElements[elementId];
                                element.removeAttribute('data-translated');
                                element.classList.remove('translator-translated');

                                // Get the original text to notify Android
                                var tempDiv = document.createElement('div');
                                tempDiv.innerHTML = originalContents[elementId];
                                var originalText = tempDiv.textContent.trim();

                                // Notify Android to update its state
                                window.AndroidTranslator.onToggleTranslation(originalText, elementId);
                                console.log('Successfully restored original content');
                                return;
                            } else {
                                console.error('Original content not found for element: ' + elementId);
                            }
                        }

                        // If we get here, either the element is not translated or we couldn't find the original content
                        // Get the complete text content of the clicked element, preserving ALL content
                        var paragraphText = element.textContent.trim();

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
                            window.AndroidTranslator.onParagraphDoubleTapped(paragraphText, elementId);
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

                            // Get the original text content
                            var tempDiv = document.createElement('div');
                            tempDiv.innerHTML = originalContents[elementId];
                            var originalTextContent = tempDiv.textContent;

                            // Count Hebrew and English characters to determine majority language
                            var hebrewMatches = originalTextContent.match(/[\u0590-\u05FF\uFB1D-\uFB4F]/g) || [];
                            var englishMatches = originalTextContent.match(/[a-zA-Z]/g) || [];
                            var hebrewCount = hebrewMatches.length;
                            var englishCount = englishMatches.length;

                            console.log('Language detection - Hebrew chars: ' + hebrewCount + ', English chars: ' + englishCount);

                            // Determine if Hebrew is the majority language
                            var isHebrewText = hebrewCount > englishCount * 1.2 || (hebrewCount > 10 && hebrewCount > englishCount * 0.5);
                            console.log('Original text majority Hebrew detection: ' + isHebrewText);

                            if (isHebrewText) {
                                // Hebrew detected, set RTL direction
                                element.style.direction = 'rtl';
                                element.style.textAlign = 'right';
                                // No green border when showing original text
                                element.style.borderRight = '';
                                element.style.paddingRight = '';
                                element.style.borderLeft = '';
                                element.style.paddingLeft = '';
                            } else {
                                // LTR language
                                element.style.direction = 'ltr';
                                element.style.textAlign = 'left';
                                element.style.borderLeft = '';
                                element.style.paddingLeft = '';
                                element.style.borderRight = '';
                                element.style.paddingRight = '';
                            }
                            // Mark element as not translated
                            window.markElementAsTranslated(elementId, false);
                            console.log('Restored original text');
                        } else {
                            // Log the full translated text for debugging
                            console.log('FULL TRANSLATED TEXT: ' + translatedText);
                            console.log('TRANSLATED TEXT LENGTH: ' + translatedText.length + ' characters');

                            // Create a text node with the translated content to preserve all formatting
                            var textNode = document.createTextNode(translatedText);

                            // Clear the element and append the new text node
                            element.innerHTML = '';
                            element.appendChild(textNode);

                            // Check if the text is Hebrew and set RTL direction
                            if (/[\u0590-\u05FF\uFB1D-\uFB4F]/.test(translatedText)) {
                                // Hebrew detected, set RTL direction
                                element.style.direction = 'rtl';
                                element.style.textAlign = 'right';
                                // For RTL, use right border instead of left
                                element.style.borderRight = '3px solid green';
                                element.style.paddingRight = '5px';
                                element.style.borderLeft = '';
                                element.style.paddingLeft = '';
                            } else {
                                // LTR language
                                element.style.direction = 'ltr';
                                element.style.textAlign = 'left';
                                element.style.borderLeft = '3px solid green';
                                element.style.paddingLeft = '5px';
                            }

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
                """.trimIndent()

                binding.webView.evaluateJavascript(jsCode, null)

                // Inline translation has been removed
            }
        }
    }

    // JavaScript interface to receive events from WebView
    inner class JavaScriptInterface {
        // We've removed the text selection handler as we're using long-press for translation popup instead

        @JavascriptInterface
        fun onParagraphDoubleTapped(paragraphText: String, elementId: String) {
            runOnUiThread {
                if (paragraphText.isNotBlank()) {
                    // Double-tap: translate paragraph inline
                    Toast.makeText(this@EpubReaderActivity, R.string.paragraph_translated, Toast.LENGTH_SHORT).show()

                    // Translate the paragraph and replace it in the WebView
                    translateAndReplaceText(paragraphText, elementId)
                }
            }
        }

        @JavascriptInterface
        fun onParagraphTripleTapped(paragraphText: String, elementId: String) {
            runOnUiThread {
                if (paragraphText.isNotBlank()) {
                    // Triple-tap: show translation dialog
                    Toast.makeText(this@EpubReaderActivity, R.string.translating, Toast.LENGTH_SHORT).show()

                    // Show translation dialog with the paragraph text
                    showTranslationDialog(paragraphText)
                }
            }
        }

        // For backward compatibility
        @JavascriptInterface
        fun onParagraphDoubleClicked(paragraphText: String, elementId: String) {
            onParagraphTripleTapped(paragraphText, elementId)
        }

        @JavascriptInterface
        fun onParagraphDoubleClicked(paragraphText: String) {
            onParagraphTripleTapped(paragraphText, "")
        }

        @JavascriptInterface
        fun onToggleTranslation(originalText: String, elementId: String) {
            runOnUiThread {
                if (originalText.isNotBlank()) {
                    // This is a toggle request from a translated paragraph
                    Log.d(TAG, "Toggle request for element: $elementId with original text: ${originalText.take(30)}...")

                    // Get the translations map for the current page
                    val pageTranslationsMap = pageTranslations[currentPage]
                    if (pageTranslationsMap != null) {
                        // Find and remove the translation for this text
                        val translationEntry = pageTranslationsMap.entries.find { it.key == originalText || it.value.contains(originalText) }
                        if (translationEntry != null) {
                            // Remove the translation
                            pageTranslationsMap.remove(translationEntry.key)
                            Log.d(TAG, "Removed translation for text: ${translationEntry.key.take(30)}...")
                        } else {
                            // Try to find by any text that contains this original text
                            val anyMatch = pageTranslationsMap.entries.find {
                                it.key.contains(originalText) || originalText.contains(it.key)
                            }
                            if (anyMatch != null) {
                                pageTranslationsMap.remove(anyMatch.key)
                                Log.d(TAG, "Removed translation for partially matching text: ${anyMatch.key.take(30)}...")
                            } else {
                                Log.d(TAG, "Could not find translation for text: ${originalText.take(30)}...")
                            }
                        }

                        // Show toast message for user feedback
                        Toast.makeText(this@EpubReaderActivity, R.string.translation_removed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // We've removed the text highlighting methods as we're now using long-press for translation popup instead

    /**
     * Translate text and replace it in the WebView
     */
    private fun translateAndReplaceText(text: String, elementId: String) {
        Log.d(TAG, "Starting translation for element: $elementId with text: ${text.take(30)}...")
        Toast.makeText(this, R.string.translating, Toast.LENGTH_SHORT).show()

        // Get the translations map for the current page
        val pageTranslationsMap = pageTranslations.getOrPut(currentPage) { mutableMapOf() }

        // If we already have a translation for this text, toggle it off
        if (pageTranslationsMap.containsKey(text)) {
            // Text is already translated, toggle back to original using our new toggleTranslation function
            val jsCode = """
                (function() {
                    return window.toggleTranslation('$elementId');
                })();
            """.trimIndent()

            binding.webView.evaluateJavascript(jsCode) { result ->
                Log.d(TAG, "Toggled back to original text for element with text: ${text.take(30)}...")
                // Remove from translations map
                pageTranslationsMap.remove(text)
            }
            return
        }

        // Start a coroutine to perform the translation
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "Calling translation service for text: ${text.take(50)}...")

                // Translate the text
                val result = withContext(Dispatchers.IO) {
                    translationManager.translateText(text)
                }

                // Get the translated text from the result
                val translatedText = result.getOrElse { throw it }

                Log.d(TAG, "Translation successful, result: ${translatedText.take(50)}...")

                // Log the text and translated text for debugging
                Log.d(TAG, "Original text length: ${text.length} characters")
                Log.d(TAG, "Original text: ${text.take(100)}...")
                Log.d(TAG, "Translated text length: ${translatedText.length} characters")
                Log.d(TAG, "Translated text: ${translatedText.take(100)}...")

                // Store the translation for this page using the text content as the key
                pageTranslationsMap[text] = translatedText

                // Set the flag to reapply translations when navigating
                needToReapplyTranslations = true

                // Use JSON.stringify to properly escape all special characters
                val jsCode = """
                    (function() {
                        var originalText = ${JSONObject().put("text", text)};
                        var translatedText = ${JSONObject().put("text", translatedText)};
                        window.replaceElementText('$elementId', originalText.text, translatedText.text);
                    })();
                """.trimIndent()

                Log.d(TAG, "Executing JavaScript for element $elementId")

                binding.webView.evaluateJavascript(jsCode) { result ->
                    Log.d(TAG, "JavaScript execution result: $result")
                    if (result == "true") {
                        Toast.makeText(this@EpubReaderActivity, R.string.paragraph_translated, Toast.LENGTH_SHORT).show()

                        // Clear WebView cache after successful translation
                        // but keep the current page content and translations
                        binding.webView.clearCache(false)
                        Log.d(TAG, "Cleared WebView cache after translation")
                    } else {
                        Toast.makeText(this@EpubReaderActivity, R.string.error_translating, Toast.LENGTH_SHORT).show()
                        // Remove from translations map if failed
                        pageTranslationsMap.remove(text)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error translating text", e)
                Toast.makeText(this@EpubReaderActivity, R.string.error_translating, Toast.LENGTH_SHORT).show()
                // Remove from translations map if failed
                pageTranslationsMap.remove(text)
            }
        }
    }

    /**
     * Apply stored translations for the current page
     */
    private fun applyStoredTranslations() {
        val pageTranslationsMap = pageTranslations[currentPage] ?: return

        if (pageTranslationsMap.isEmpty()) {
            Log.d(TAG, "No stored translations for page $currentPage")
            return
        }

        Log.d(TAG, "Applying ${pageTranslationsMap.size} stored translations for page $currentPage")

        // First, let's inject a helper function to find elements by text content
        val helperJsCode = """
            (function() {
                // Function to find elements by their text content
                window.findElementsByText = function(searchText) {
                    var allElements = document.querySelectorAll('p, span, div, h1, h2, h3, h4, h5, h6, li, td, th');
                    var matchingElements = [];

                    for (var i = 0; i < allElements.length; i++) {
                        var element = allElements[i];
                        var elementText = element.textContent.trim();

                        // Check if this element has the exact text we're looking for
                        if (elementText === searchText) {
                            // Make sure it has a translator ID
                            if (!element.hasAttribute('data-translator-id')) {
                                var elementId = 'translator-' + Math.random().toString(36).substr(2, 9);
                                element.setAttribute('data-translator-id', elementId);
                            }
                            matchingElements.push(element);
                        }
                    }

                    console.log('Found ' + matchingElements.length + ' elements with text: ' + searchText.substring(0, 30) + '...');
                    return matchingElements;
                };

                // Function to apply translation to all matching elements
                window.applyTranslationByText = function(originalText, translatedText) {
                    var elements = window.findElementsByText(originalText);
                    var success = false;

                    for (var i = 0; i < elements.length; i++) {
                        var element = elements[i];
                        var elementId = element.getAttribute('data-translator-id');

                        console.log('Applying translation to element: ' + elementId);

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

                        // Mark this element as translated
                        window.markElementAsTranslated(elementId, true);

                        // Create a text node with the translated content
                        var textNode = document.createTextNode(translatedText);

                        // Clear the element and append the new text node
                        element.innerHTML = '';
                        element.appendChild(textNode);

                        // Check if the text is Hebrew and set RTL direction
                        if (/[\u0590-\u05FF\uFB1D-\uFB4F]/.test(translatedText)) {
                            // Hebrew detected, set RTL direction
                            element.style.direction = 'rtl';
                            element.style.textAlign = 'right';
                            // For RTL, use right border instead of left
                            element.style.borderRight = '3px solid green';
                            element.style.paddingRight = '5px';
                            element.style.borderLeft = '';
                            element.style.paddingLeft = '';
                        } else {
                            // LTR language
                            element.style.direction = 'ltr';
                            element.style.textAlign = 'left';
                            element.style.borderLeft = '3px solid green';
                            element.style.paddingLeft = '5px';
                        }

                        // Add visual indicators
                        element.style.backgroundColor = 'rgba(144, 238, 144, 0.2)';
                        translatedElements[elementId] = true;
                        success = true;
                    }

                    return success;
                };
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(helperJsCode, null)

        // Wait a moment for the helper function to be available
        Handler(Looper.getMainLooper()).postDelayed({
            // Apply each stored translation
            for ((originalText, translatedText) in pageTranslationsMap) {
                // Use JSON.stringify to properly escape all special characters
                val jsCode = """
                    (function() {
                        var originalText = ${JSONObject().put("text", originalText)};
                        var translatedText = ${JSONObject().put("text", translatedText)};
                        return window.applyTranslationByText(originalText.text, translatedText.text);
                    })();
                """.trimIndent()

                binding.webView.evaluateJavascript(jsCode) { result ->
                    val success = result.trim() == "true"
                    if (success) {
                        Log.d(TAG, "Successfully applied translation for text: ${originalText.take(30)}...")
                    } else {
                        Log.d(TAG, "Failed to apply translation for text: ${originalText.take(30)}...")
                    }
                }
            }

            // Reset the flag after applying translations
            needToReapplyTranslations = false
        }, 500) // Increased delay to ensure the page is fully loaded and processed
    }

    /**
     * Show translation dialog
     */
    private fun showTranslationDialog(text: String) {
        TranslationDialog(this, text, translationManager).show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Save the current position before exiting
        saveCurrentPosition()
        // Clear all translations and cache when exiting
        clearAllTranslationsAndCache()
        super.onBackPressed()
    }

    /**
     * Clear all stored translations and cache
     */
    private fun clearAllTranslationsAndCache() {
        Log.d(TAG, "Clearing all stored translations and cache")
        // Clear translations
        pageTranslations.clear()
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
        // Clear all translations and cache when the activity is destroyed
        clearAllTranslationsAndCache()

        // Clear WebView cache completely
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.webView.clearFormData()

        // Clear WebView cookies
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()

        Log.d(TAG, "Cleared all WebView cache and cookies on destroy")

        super.onDestroy()
    }

    // Variable to store the scroll position for each page
    private val pageScrollPositions = mutableMapOf<Int, Int>()

    // Data structure to store translations for each page
    // Map of page index to a map of element text to translated text
    // Using the text content as the key instead of element ID for more reliable matching
    private val pageTranslations = mutableMapOf<Int, MutableMap<String, String>>()

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
     * Set up gesture detector for swipe navigation
     */
    private fun setupGestureDetector() {
        // Set up a simple touch listener for the WebView
        var startX = 0f
        var startY = 0f

        binding.webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.x
                    val endY = event.y

                    val diffX = endX - startX
                    val diffY = endY - startY

                    // Check if it's a horizontal swipe
                    if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100) {
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
                    false
                }
                else -> false
            }
        }
    }
}
