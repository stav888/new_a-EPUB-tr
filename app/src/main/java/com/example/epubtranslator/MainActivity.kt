package com.example.epubtranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epubtranslator.adapter.RecentBooksAdapter
import com.example.epubtranslator.data.RecentBook
import com.example.epubtranslator.data.RecentBooksManager
import com.example.epubtranslator.databinding.ActivityMainBinding
import com.example.epubtranslator.translation.TranslationApi
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var recentBooksManager: RecentBooksManager
    private lateinit var recentBooksAdapter: RecentBooksAdapter
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var thumbnailGenerator: ThumbnailGenerator
    private lateinit var translationManager: TranslationManager

    // View mode constants
    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val VIEW_MODE_LIST = 0
        private const val VIEW_MODE_GRID = 1
    }

    private var currentViewMode = VIEW_MODE_LIST

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

        // Set up toolbar
        setSupportActionBar(binding.toolbar)

        // Add a title to the toolbar
        title = getString(R.string.app_name)

        // Set up navigation drawer
        setupNavigationDrawer()

        // Initialize managers
        recentBooksManager = RecentBooksManager(this)
        thumbnailGenerator = ThumbnailGenerator(this)
        translationManager = TranslationManager(this)

        // Set up API selection dropdown
        setupApiSelector()

        // Load view mode preference
        loadViewModePreference()

        // Set up the toggle view button
        binding.toggleViewButton.setOnClickListener {
            toggleViewMode()
        }

        // Update the view mode icons
        updateViewModeIcons()

        // Set up RecyclerView for recent books
        setupRecentBooksRecyclerView()

        // Show a toast to inform the user about the navigation drawer
        Toast.makeText(this, "Swipe from left edge or tap the menu icon to access options", Toast.LENGTH_LONG).show()
    }

    /**
     * Set up the API selection dropdown
     */
    private fun setupApiSelector() {
        // Get the spinner from the layout
        val apiSpinner: Spinner = binding.apiSelector

        // Create an array adapter with the API options
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            arrayOf(getString(R.string.google_api), getString(R.string.yandex_api))
        )

        // Set the dropdown layout style
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Apply the adapter to the spinner
        apiSpinner.adapter = adapter

        // Set the current API as the selected item
        val currentApi = translationManager.getCurrentApi()
        apiSpinner.setSelection(if (currentApi == TranslationApi.GOOGLE) 0 else 1)

        // Set a listener for API selection changes
        apiSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Convert position to API enum
                val selectedApi = if (position == 0) TranslationApi.GOOGLE else TranslationApi.YANDEX

                // Only update if the API has changed
                if (selectedApi != translationManager.getCurrentApi()) {
                    // Update the translation manager with the selected API
                    translationManager.setTranslationApi(selectedApi)

                    // Show a toast to confirm the change
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.api_changed, getString(if (selectedApi == TranslationApi.GOOGLE) R.string.google_api else R.string.yandex_api)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun setupNavigationDrawer() {
        // Set up the toolbar with a custom drawer icon
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu_drawer)

        // Create the ActionBarDrawerToggle
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
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
            onBookLongClick = { recentBook ->
                // Show delete confirmation dialog
                showDeleteBookDialog(recentBook)
                true
            }
        )

        // Set the appropriate layout manager based on view mode
        setRecyclerViewLayoutManager()

        binding.recentBooksRecyclerView.adapter = recentBooksAdapter

        updateRecentBooksList()
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

        // Update the adapter with the new view mode
        recentBooksAdapter.setViewMode(currentViewMode)

        // Update the layout manager
        setRecyclerViewLayoutManager()

        // Save the preference
        saveViewModePreference()

        // Update the icons
        updateViewModeIcons()

        // Show a toast to indicate the view mode change
        val message = if (currentViewMode == VIEW_MODE_LIST) R.string.list_view else R.string.grid_view
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateViewModeIcons() {
        // Update the toggle button icon based on the current view mode
        // When in list mode, show the grid icon (to switch to grid)
        // When in grid mode, show the list icon (to switch to list)
        binding.toggleViewButton.setImageResource(
            if (currentViewMode == VIEW_MODE_LIST) R.drawable.ic_view_grid
            else R.drawable.ic_view_list
        )

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

    private fun updateRecentBooksList() {
        val recentBooks = recentBooksManager.getRecentBooks()
        recentBooksAdapter.updateBooks(recentBooks)

        // Show/hide the "No recent books" message
        if (recentBooks.isEmpty()) {
            binding.noRecentBooksTextView.visibility = View.VISIBLE
            binding.recentBooksRecyclerView.visibility = View.GONE
        } else {
            binding.noRecentBooksTextView.visibility = View.GONE
            binding.recentBooksRecyclerView.visibility = View.VISIBLE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        // Handle other menu items
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
        }

        return super.onOptionsItemSelected(item)
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
            R.id.nav_about -> {
                // Show an about dialog
                showAboutDialog()
            }
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun showAboutDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle(R.string.about)
        builder.setMessage("EPUB Translator\n\nVersion 1.0\n\nA simple EPUB reader with translation capabilities.\n\nSupports double-tap on paragraphs for translation.")
        builder.setPositiveButton("OK", null)
        builder.show()
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
            // Get the file name from the URI
            val fileName = getFileNameFromUri(uri) ?: "book_${System.currentTimeMillis()}.epub"

            // Create a unique file in the app's files directory
            val booksDir = File(filesDir, "books")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            // Create a file with the original name (or a timestamp if name not available)
            val bookFile = File(booksDir, fileName)

            // Copy the file content
            val inputStream = contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                FileOutputStream(bookFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Generate thumbnail and add to recent books
            CoroutineScope(Dispatchers.Main).launch {
                val thumbnailPath = withContext(Dispatchers.IO) {
                    thumbnailGenerator.generateThumbnail(bookFile.absolutePath)
                }

                // Add to recent books with thumbnail
                recentBooksManager.addRecentBook(bookFile.absolutePath, thumbnailPath)

                // Update the recent books list
                updateRecentBooksList()
            }

            // Start EpubReaderActivity with the file path
            openEpubFromFile(bookFile)

        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_loading_epub, Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        // Try to get the display name from the content resolver
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    return it.getString(displayNameIndex)
                }
            }
        }

        // If that fails, try to get it from the URI path
        uri.path?.let { path ->
            val cut = path.lastIndexOf('/')
            if (cut != -1) {
                return path.substring(cut + 1)
            }
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

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
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
        when (intent.action) {
            // Handle ACTION_VIEW intents (opening EPUB files from file browser)
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.toString().endsWith(".epub", ignoreCase = true) ||
                        intent.type == "application/epub+zip") {
                        openEpubFile(uri)
                    }
                }
            }

            // Handle ACTION_SEND intents (sharing EPUB files from other apps)
            Intent.ACTION_SEND -> {
                if (intent.type == "application/epub+zip") {
                    // Get the URI from the intent extras
                    // Use the appropriate method based on Android version
                    val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }

                    uri?.let {
                        openEpubFile(it)
                    }
                }
            }
        }
    }
}
