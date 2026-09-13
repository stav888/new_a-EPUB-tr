package com.example.epubtranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.appcompat.widget.PopupMenu
import android.content.res.ColorStateList
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epubtranslator.adapter.RecentBooksAdapter
import com.example.epubtranslator.data.RecentBook
import com.example.epubtranslator.data.RecentBooksManager
import com.example.epubtranslator.databinding.ActivityMainBinding
import com.example.epubtranslator.translation.TranslationManager
import com.example.epubtranslator.util.ThumbnailGenerator
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val VIEW_MODE_LIST = 0
        private const val VIEW_MODE_GRID = 1
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentBooksManager: RecentBooksManager
    private lateinit var recentBooksAdapter: RecentBooksAdapter
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var thumbnailGenerator: ThumbnailGenerator
    private lateinit var translationManager: TranslationManager

    private var currentViewMode = VIEW_MODE_LIST
    private var allBooks: List<RecentBook> = emptyList()
    private var filteredBooks: List<RecentBook> = emptyList()
    private var currentFilter = ""
    private var currentSearchQuery = ""

    // Register for activity result to handle file selection
    private val selectEpubLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                openEpubFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle incoming intents (for shared EPUB files)
        handleIntent(intent)

        // Set up toolbar (removed from layout, using header instead)

        // Add a title to the toolbar
        title = getString(R.string.app_name)

        // Set up navigation drawer
        setupNavigationDrawer()

        // Initialize managers
        recentBooksManager = RecentBooksManager(this)
        thumbnailGenerator = ThumbnailGenerator(this)
        translationManager = TranslationManager(this)

        // Set up version information
        setupVersionInfo()

        // Load view mode preference
        loadViewModePreference()

        // Set up the new view toggle button group
        setupViewToggleButtons()

        // Update the view toggle icon
        updateViewToggleIcon()

        // Set up RecyclerView for recent books
        setupRecentBooksRecyclerView()

        // Set up search and filter functionality
        setupSearchAndFilters()

        // Show a toast to inform the user about the navigation drawer
        Toast.makeText(this, "Swipe from left edge or tap the menu icon to access options", Toast.LENGTH_LONG).show()

        // Set up back pressed callback
        setupBackPressedCallback()
    }

    /**
     * Set up version information display
     */
    private fun setupVersionInfo() {
        try {
            // Get version information from package manager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = packageInfo.versionName ?: BuildConfig.VERSION_NAME

            // versionName already contains "build X" format from build.gradle
            // Don't add duplicate "build" text
            val displayVersion = versionName

            // Update the navigation menu item with current version
            val menu = binding.navigationView.menu
            val versionItem = menu.findItem(R.id.nav_version)
            versionItem?.title = "Version $displayVersion"

            // Update the header version text
            val headerView = binding.navigationView.getHeaderView(0)
            val headerVersionText = headerView.findViewById<TextView>(R.id.navHeaderVersionText)
            headerVersionText?.text = "v$displayVersion"

        } catch (e: Exception) {
            // If we can't get version info, use default
            val menu = binding.navigationView.menu
            val versionItem = menu.findItem(R.id.nav_version)
            versionItem?.title = "Version ${BuildConfig.VERSION_NAME}"

            // Update header with fallback
            val headerView = binding.navigationView.getHeaderView(0)
            val headerVersionText = headerView.findViewById<TextView>(R.id.navHeaderVersionText)
            headerVersionText?.text = "v${BuildConfig.VERSION_NAME}"
        }
    }

    private fun setupNavigationDrawer() {
        // Create the ActionBarDrawerToggle (without toolbar since we're using custom header)
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        // Add the drawer toggle as a drawer listener
        binding.drawerLayout.addDrawerListener(drawerToggle)

        // Disable the animated hamburger icon
        drawerToggle.isDrawerIndicatorEnabled = false
        drawerToggle.syncState()

        // Set the navigation item selected listener
        binding.navigationView.setNavigationItemSelectedListener(this)

        // Set up the FAB to open the drawer
        binding.fabMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Set up the add book button in header
        binding.addBookButton.setOnClickListener {
            openFilePicker()
        }

        // Set up the empty state add button
        binding.emptyStateAddButton.setOnClickListener {
            openFilePicker()
        }

        // Open the drawer once to show the user it exists
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun setupRecentBooksRecyclerView() {
        // Set up the RecyclerView with the current view mode

        recentBooksAdapter = RecentBooksAdapter(
            books = emptyList(),
            viewMode = currentViewMode,
            onBookClick = { recentBook ->
                // Open the selected book
                val file = File(recentBook.filePath)
                if (file.exists()) {
                    openEpubFromFile(file)
                } else {
                    Toast.makeText(this, R.string.error_loading_epub, Toast.LENGTH_SHORT).show()
                    // Remove the book from recent books if it no longer exists
                    recentBooksManager.removeRecentBook(recentBook.filePath)
                    updateRecentBooksList()
                }
            },
            onBookLongClick = { anchorView, recentBook ->
                // Show context menu anchored to the pressed cover view
                showBookContextMenu(anchorView, recentBook)
                true
            }
        )

        // Set the appropriate layout manager based on view mode
        setRecyclerViewLayoutManager()

        binding.recentBooksRecyclerView.adapter = recentBooksAdapter

        updateRecentBooksList()
    }

    private fun showBookContextMenu(anchor: View, recentBook: RecentBook) {
        Log.d("MainActivity", "showBookContextMenu called for book: ${recentBook.title}")

        try {
            val popup = PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.book_context_menu, popup.menu)

            popup.setOnMenuItemClickListener { item ->
                Log.d("MainActivity", "Context menu item clicked: ${item.title}")
                when (item.itemId) {
                    R.id.menu_share_book -> {
                        shareBook(recentBook)
                        true
                    }
                    R.id.menu_delete_book -> {
                        showDeleteBookDialog(recentBook)
                        true
                    }
                    else -> false
                }
            }

            popup.show()
            Log.d("MainActivity", "Context menu shown successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing context menu", e)
            Toast.makeText(this, "Error showing menu: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareBook(recentBook: RecentBook) {
        val file = File(recentBook.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Book file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Sharing: ${recentBook.title}")
                putExtra(Intent.EXTRA_TEXT, "Check out this book: ${recentBook.title}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Book"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Error sharing book", e)
            Toast.makeText(this, "Error sharing book", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteBookDialog(book: RecentBook) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(R.string.delete_book)
        builder.setMessage(getString(R.string.delete_book_confirmation, book.title))

        builder.setPositiveButton(R.string.delete) { _, _ ->
            // Delete the book file
            val file = File(book.filePath)
            if (file.exists()) {
                file.delete()
            }

            // Remove from recent books
            recentBooksManager.removeRecentBook(book.filePath)

            // Delete thumbnail if it exists
            book.coverImagePath?.let { thumbnailPath ->
                val thumbnailFile = File(thumbnailPath)
                if (thumbnailFile.exists()) {
                    thumbnailFile.delete()
                }
            }

            // Update the UI
            updateRecentBooksList()

            Toast.makeText(this, R.string.book_deleted, Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton(R.string.cancel, null)

        builder.show()
    }

    private fun setRecyclerViewLayoutManager() {
        binding.recentBooksRecyclerView.layoutManager = when (currentViewMode) {
            VIEW_MODE_LIST -> LinearLayoutManager(this)
            VIEW_MODE_GRID -> GridLayoutManager(this, calculateGridSpanCount())
            else -> LinearLayoutManager(this)
        }
    }

    private fun calculateGridSpanCount(): Int {
        // Calculate how many columns can fit on the screen based on device width
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density

        // Assume each grid item should be about 160dp wide
        return (screenWidthDp / 160).toInt().coerceAtLeast(2)
    }

    private fun loadViewModePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentViewMode = prefs.getInt(KEY_VIEW_MODE, VIEW_MODE_LIST)
    }

    private fun saveViewModePreference() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putInt(KEY_VIEW_MODE, currentViewMode).apply()
    }

    private fun toggleViewMode() {
        currentViewMode = if (currentViewMode == VIEW_MODE_LIST) VIEW_MODE_GRID else VIEW_MODE_LIST

        val recyclerView = binding.recentBooksRecyclerView
        recyclerView.stopScroll()
        recyclerView.adapter = null
        recyclerView.recycledViewPool.clear()

        recentBooksAdapter.setViewMode(currentViewMode)
        setRecyclerViewLayoutManager()
        recyclerView.adapter = recentBooksAdapter

        // Save the preference
        saveViewModePreference()

        // Update the icon
        updateViewToggleIcon()

        // Show a toast to indicate the view mode change
        val message = if (currentViewMode == VIEW_MODE_LIST) R.string.list_view else R.string.grid_view
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupViewToggleButtons() {
        // Set up single view toggle button
        binding.viewToggleButton.setOnClickListener {
            toggleViewMode()
        }

        // Initialize the button icon
        updateViewToggleIcon()
    }



    private fun updateViewToggleIcon() {
        // Show the icon for the mode we can switch TO
        // When in list view, show grid icon (to switch to grid)
        // When in grid view, show list icon (to switch to list)
        if (currentViewMode == VIEW_MODE_LIST) {
            // Currently in list view - show grid icon to switch to grid
            binding.viewToggleButton.setIconResource(R.drawable.ic_view_grid)
        } else {
            // Currently in grid view - show list icon to switch to list
            binding.viewToggleButton.setIconResource(R.drawable.ic_view_list)
        }

        // Also update the menu icon
        invalidateOptionsMenu()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val toggleItem = menu.findItem(R.id.action_toggle_view)
        if (toggleItem != null) {
            // Set the icon based on the current view mode
            toggleItem.setIcon(
                if (currentViewMode == VIEW_MODE_LIST) R.drawable.ic_view_grid
                else R.drawable.ic_view_list
            )
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private var isGeneratingMissingThumbnails = false

    private fun setupSearchAndFilters() {
        // Set up search functionality
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyFiltersAndSearch()
            }
        })

    }

    private fun applyFiltersAndSearch() {
        var books = allBooks

        // Apply filter (updated for Stitch design categories)
        books = when (currentFilter) {
            "Read" -> books.sortedByDescending { it.lastOpenedTimestamp }
            "Currently Reading" -> books.filter { it.lastOpenedTimestamp > 0 }.sortedByDescending { it.lastOpenedTimestamp }
            "To Read" -> books.filter { it.lastOpenedTimestamp == 0L }.sortedBy { it.title }
            else -> books
        }

        // Apply search
        if (currentSearchQuery.isNotEmpty()) {
            books = books.filter { book ->
                book.title.contains(currentSearchQuery, ignoreCase = true) ||
                book.filePath.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        filteredBooks = books
        recentBooksAdapter.updateBooks(filteredBooks)

        // Show/hide the "No recent books" message
        if (filteredBooks.isEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.recentBooksRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.recentBooksRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateRecentBooksList() {
        allBooks = recentBooksManager.getRecentBooks()
        // Diagnostic logging for covers
        allBooks.forEachIndexed { index, book ->
            val path = book.coverImagePath
            val exists = if (path != null) File(path).exists() else false
            Log.d("MainActivity", "Book[$index] title='${book.title}' coverPath=$path exists=$exists")
        }

        if (!isGeneratingMissingThumbnails) {
            generateMissingThumbnailsIfNeeded(allBooks)
        }

        // Apply current filters and search
        applyFiltersAndSearch()
    }

    private fun generateMissingThumbnailsIfNeeded(recentBooks: List<RecentBook>) {
        val booksNeedingThumbnails = recentBooks.filter { book ->
            val path = book.coverImagePath
            path.isNullOrBlank() || (path != null && (!java.io.File(path).exists() || !path.contains("_thumb_v2.")))
        }

        if (booksNeedingThumbnails.isEmpty()) return

        Log.d("MainActivity", "Found ${booksNeedingThumbnails.size} books needing thumbnails")

        isGeneratingMissingThumbnails = true
        CoroutineScope(Dispatchers.Main).launch {
            var thumbnailsGenerated = 0

            for (book in booksNeedingThumbnails) {
                try {
                    val thumbnailPath = withContext(Dispatchers.IO) {
                        thumbnailGenerator.generateThumbnail(book.filePath)
                    }

                    if (!thumbnailPath.isNullOrBlank()) {
                        Log.d("MainActivity", "Generated thumbnail for ${book.title}: $thumbnailPath")
                        // Update the book with the new thumbnail path
                        recentBooksManager.addRecentBook(book.filePath, thumbnailPath)
                        thumbnailsGenerated++

                        // Update the UI incrementally for better user experience
                        val updatedBooks = recentBooksManager.getRecentBooks()
                        recentBooksAdapter.updateBooks(updatedBooks)
                    } else {
                        Log.w("MainActivity", "Failed to generate thumbnail for ${book.title}")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error generating thumbnail for ${book.title}", e)
                }
            }

            isGeneratingMissingThumbnails = false

            // Final update to ensure all changes are reflected
            val finalBooks = recentBooksManager.getRecentBooks()
            recentBooksAdapter.updateBooks(finalBooks)

            if (thumbnailsGenerated > 0) {
                Toast.makeText(this@MainActivity,
                    "Generated $thumbnailsGenerated book cover${if (thumbnailsGenerated > 1) "s" else ""}",
                    Toast.LENGTH_SHORT).show()
            }

            Log.d("MainActivity", "Thumbnail generation complete. Generated: $thumbnailsGenerated")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        when (item.itemId) {
            android.R.id.home -> {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    binding.drawerLayout.openDrawer(GravityCompat.START)
                }
                return true
            }
            R.id.action_toggle_view -> {
                toggleViewMode()
                return true
            }
            R.id.action_menu -> {
                binding.drawerLayout.openDrawer(GravityCompat.START)
                return true
            }
            R.id.action_refresh_covers -> {
                refreshAllCovers()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refreshAllCovers() {
        if (isGeneratingMissingThumbnails) {
            Toast.makeText(this, "Already generating covers...", Toast.LENGTH_SHORT).show()
            return
        }
        val books = recentBooksManager.getRecentBooks()
        if (books.isEmpty()) {
            Toast.makeText(this, "No books to refresh", Toast.LENGTH_SHORT).show()
            return
        }
        isGeneratingMissingThumbnails = true
        Toast.makeText(this, "Refreshing covers...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            var regenerated = 0
            withContext(Dispatchers.IO) {
                books.forEach { book ->
                    try {
                        // Delete old thumbnail if exists
                        book.coverImagePath?.let { oldPath ->
                            val f = File(oldPath)
                            if (f.exists()) f.delete()
                        }
                        val newPath = thumbnailGenerator.generateThumbnail(book.filePath)
                        if (!newPath.isNullOrBlank()) {
                            recentBooksManager.addRecentBook(book.filePath, newPath)
                            regenerated++
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to regenerate cover for ${book.title}", e)
                    }
                }
            }
            isGeneratingMissingThumbnails = false
            updateRecentBooksList()
            Toast.makeText(this@MainActivity, "Refreshed $regenerated cover${if (regenerated!=1) "s" else ""}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_select_epub -> {
                openFilePicker()
            }
            R.id.nav_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/epub+zip"
        }
        selectEpubLauncher.launch(intent)
    }

    private fun openEpubFile(uri: Uri) {
        try {
            val fileName = getFileNameFromUri(uri) ?: "book_${System.currentTimeMillis()}.epub"
            val booksDir = File(filesDir, "books")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }
            val bookFile = File(booksDir, fileName)
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Toast.makeText(this, "Cannot access the selected file", Toast.LENGTH_SHORT).show()
                return
            }
            inputStream.use { input ->
                FileOutputStream(bookFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Directly open (openEpubFromFile handles thumbnail generation and recent list)
            openEpubFromFile(bookFile)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening EPUB file", e)
            Toast.makeText(this, R.string.error_loading_epub, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        try {
            // Try to get the display name from the content resolver
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        val displayName = it.getString(displayNameIndex)
                        if (!displayName.isNullOrBlank()) {
                            return displayName
                        }
                    }
                }
            }

            // If that fails, try to get it from the URI path
            uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1 && cut < path.length - 1) {
                    return path.substring(cut + 1)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting filename from URI", e)
        }

        return null
    }

    private fun openEpubFromFile(file: File) {
        // Update the book's last opened timestamp
        CoroutineScope(Dispatchers.Main).launch {
            // Generate thumbnail if it doesn't exist
            val thumbnailPath = withContext(Dispatchers.IO) {
                thumbnailGenerator.generateThumbnail(file.absolutePath)
            }

            // Update recent books with the new timestamp
            recentBooksManager.addRecentBook(file.absolutePath, thumbnailPath)

            // Update the UI
            updateRecentBooksList()
        }

        // Start the EPUB reader activity
        val intent = Intent(this, EpubReaderActivity::class.java).apply {
            putExtra(EpubReaderActivity.EXTRA_EPUB_FILE_PATH, file.absolutePath)
        }
        startActivity(intent)
    }

    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Update the recent books list when returning to the activity
        updateRecentBooksList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle the new intent
        handleIntent(intent)
    }

    /**
     * Handle incoming intents for EPUB files
     */
    private fun handleIntent(intent: Intent) {
        Log.d("MainActivity", "Handling intent: action=${intent.action}, type=${intent.type}, data=${intent.data}")

        when (intent.action) {
            // Handle ACTION_VIEW intents (opening EPUB files from file browser)
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    Log.d("MainActivity", "ACTION_VIEW with URI: $uri")

                    // Check if it's an EPUB file by URI path or MIME type
                    val isEpubFile = uri.toString().endsWith(".epub", ignoreCase = true) ||
                                   intent.type == "application/epub+zip" ||
                                   uri.path?.endsWith(".epub", ignoreCase = true) == true

                    if (isEpubFile) {
                        Log.d("MainActivity", "Opening EPUB file from ACTION_VIEW")
                        openEpubFile(uri)
                    } else {
                        Log.w("MainActivity", "URI does not appear to be an EPUB file: $uri")
                        Toast.makeText(this, "This file does not appear to be an EPUB file", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // Handle ACTION_SEND intents (sharing EPUB files from other apps)
            Intent.ACTION_SEND -> {
                Log.d("MainActivity", "ACTION_SEND with type: ${intent.type}")

                if (intent.type == "application/epub+zip" || intent.type == "application/octet-stream") {
                    // Get the URI from the intent extras
                    // Use the appropriate method based on Android version
                    val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }

                    uri?.let {
                        Log.d("MainActivity", "Opening EPUB file from ACTION_SEND: $it")
                        openEpubFile(it)
                    } ?: run {
                        Log.w("MainActivity", "No URI found in ACTION_SEND intent")
                        Toast.makeText(this, "No file found to open", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w("MainActivity", "Unsupported MIME type for ACTION_SEND: ${intent.type}")
                    Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
                }
            }

            else -> {
                Log.d("MainActivity", "Unhandled intent action: ${intent.action}")
            }
        }
    }
}
