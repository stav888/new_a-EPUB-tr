package com.example.epubtranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.epubtranslator.databinding.ActivitySearchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.util.regex.Pattern

/**
 * Simplified search activity for EPUB content
 */
class SimpleSearchActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_BOOK_PATH = "book_path"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_HTML_FILES = "html_files"
        const val RESULT_NAVIGATE_TO_PAGE = "navigate_to_page"
        const val RESULT_PAGE_INDEX = "page_index"
        const val RESULT_SEARCH_QUERY = "search_query"
    }
    
    private lateinit var binding: ActivitySearchBinding
    private lateinit var resultsAdapter: SimpleSearchResultsAdapter
    
    private var bookPath: String = ""
    private var bookTitle: String = ""
    private var htmlFiles: List<String> = emptyList()
    
    private var searchJob: Job? = null
    private var currentResults: List<SimpleSearchResult> = emptyList()
    private var searchHistory: MutableList<String> = mutableListOf()
    private var suggestionsAdapter: SearchSuggestionsAdapter? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get intent data
        bookPath = intent.getStringExtra(EXTRA_BOOK_PATH) ?: ""
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: ""
        htmlFiles = intent.getStringArrayListExtra(EXTRA_HTML_FILES) ?: emptyList()

        Log.d("SimpleSearchActivity", "🔍 Search activity created")
        Log.d("SimpleSearchActivity", "🔍 Book path: $bookPath")
        Log.d("SimpleSearchActivity", "🔍 Book title: $bookTitle")
        Log.d("SimpleSearchActivity", "🔍 HTML files received: ${htmlFiles.size}")

        if (htmlFiles.isEmpty()) {
            Log.w("SimpleSearchActivity", "🔍 Warning: No HTML files received!")
        }

        setupUI()
        setupRecyclerView()
        loadSearchHistory()
        setupSuggestions()
    }
    
    private fun setupUI() {
        // Setup toolbar
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
        
        binding.toolbar.title = "Search in $bookTitle"
        
        // Setup search input
        binding.searchEditText.addTextChangedListener { text ->
            val query = text.toString().trim()
            if (query.length >= 2) {
                scheduleSearch(query)
                // Hide suggestions when typing
                binding.suggestionsCard.visibility = View.GONE
            } else {
                clearResults()
                // Show suggestions when input is empty
                updateSuggestions()
            }
        }
        
        binding.searchEditText.setOnEditorActionListener { _, _, _ ->
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearch(query)
            }
            true
        }
        
        // Setup search options
        binding.caseSensitiveChip.setOnCheckedChangeListener { _, _ ->
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearch(query)
            }
        }
        
        binding.wholeWordsChip.setOnCheckedChangeListener { _, _ ->
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                performSearch(query)
            }
        }
    }
    
    private fun setupRecyclerView() {
        resultsAdapter = SimpleSearchResultsAdapter { result ->
            navigateToResult(result)
        }
        
        binding.resultsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SimpleSearchActivity)
            adapter = resultsAdapter
        }
    }
    
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(300) // Debounce search
            performSearch(query)
        }
    }
    
    private fun performSearch(query: String) {
        if (query.isBlank()) return

        // Add to search history
        addToSearchHistory(query)

        showLoading(true)

        lifecycleScope.launch {
            try {
                val caseSensitive = binding.caseSensitiveChip.isChecked
                val wholeWords = binding.wholeWordsChip.isChecked

                val results = searchInContent(query, caseSensitive, wholeWords)
                currentResults = results
                displayResults(results, query)

            } catch (e: Exception) {
                Toast.makeText(this@SimpleSearchActivity, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                showEmptyState()
            } finally {
                showLoading(false)
            }
        }
    }
    
    private fun searchInContent(query: String, caseSensitive: Boolean, wholeWords: Boolean): List<SimpleSearchResult> {
        val results = mutableListOf<SimpleSearchResult>()
        val pattern = createSearchPattern(query, caseSensitive, wholeWords)
        
        htmlFiles.forEachIndexed { pageIndex, htmlContent ->
            try {
                val doc = Jsoup.parse(htmlContent)
                val textContent = doc.text()
                
                val matcher = pattern.matcher(textContent)
                var matchCount = 0
                
                while (matcher.find() && matchCount < 10) { // Limit matches per page
                    val matchStart = matcher.start()
                    val matchEnd = matcher.end()
                    val matchedText = textContent.substring(matchStart, matchEnd)
                    
                    // Extract context around the match
                    val contextStart = maxOf(0, matchStart - 100)
                    val contextEnd = minOf(textContent.length, matchEnd + 100)
                    val context = textContent.substring(contextStart, contextEnd)
                    
                    // Highlight the matched text in context
                    val highlightedContext = highlightMatchInContext(context, matchedText, caseSensitive)
                    
                    results.add(
                        SimpleSearchResult(
                            pageIndex = pageIndex,
                            pageTitle = "Page ${pageIndex + 1}",
                            matchedText = matchedText,
                            context = highlightedContext,
                            position = matchStart
                        )
                    )
                    
                    matchCount++
                }
                
            } catch (e: Exception) {
                // Continue with next page
            }
        }
        
        return results
    }
    
    private fun createSearchPattern(query: String, caseSensitive: Boolean, wholeWords: Boolean): Pattern {
        var patternString = Pattern.quote(query)
        
        if (wholeWords) {
            patternString = "\\b$patternString\\b"
        }
        
        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        return Pattern.compile(patternString, flags)
    }
    
    private fun highlightMatchInContext(context: String, matchedText: String, caseSensitive: Boolean): String {
        val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        val pattern = Pattern.compile(Pattern.quote(matchedText), flags)
        return pattern.matcher(context).replaceAll("<mark>$0</mark>")
    }
    
    private fun displayResults(results: List<SimpleSearchResult>, query: String) {
        if (results.isEmpty()) {
            showNoResults(query)
        } else {
            showResults(results)
        }
    }
    
    private fun showResults(results: List<SimpleSearchResult>) {
        binding.emptyStateContainer.visibility = View.GONE
        binding.noResultsContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.VISIBLE
        
        binding.resultsCountText.text = "${results.size} result${if (results.size != 1) "s" else ""} found"
        resultsAdapter.submitList(results)
    }
    
    private fun showNoResults(query: String) {
        binding.emptyStateContainer.visibility = View.GONE
        binding.resultsContainer.visibility = View.GONE
        binding.noResultsContainer.visibility = View.VISIBLE
        
        binding.noResultsSubtext.text = "No results found for \"$query\""
    }
    
    private fun showEmptyState() {
        binding.resultsContainer.visibility = View.GONE
        binding.noResultsContainer.visibility = View.GONE
        binding.emptyStateContainer.visibility = View.VISIBLE
    }
    
    private fun clearResults() {
        currentResults = emptyList()
        resultsAdapter.submitList(emptyList())
        showEmptyState()
    }
    
    private fun showLoading(show: Boolean) {
        binding.searchProgress.visibility = if (show) View.VISIBLE else View.GONE
    }
    
    private fun navigateToResult(result: SimpleSearchResult) {
        val resultIntent = Intent().apply {
            putExtra(RESULT_NAVIGATE_TO_PAGE, true)
            putExtra(RESULT_PAGE_INDEX, result.pageIndex)
            putExtra(RESULT_SEARCH_QUERY, result.matchedText)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun loadSearchHistory() {
        val prefs = getSharedPreferences("search_history", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("history_$bookPath", emptySet()) ?: emptySet()
        searchHistory.clear()
        searchHistory.addAll(historySet.take(10)) // Limit to 10 recent searches
    }

    private fun saveSearchHistory() {
        val prefs = getSharedPreferences("search_history", Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("history_$bookPath", searchHistory.toSet())
            .apply()
    }

    private fun addToSearchHistory(query: String) {
        if (query.isBlank()) return

        // Remove if already exists to move to top
        searchHistory.remove(query)
        // Add to beginning
        searchHistory.add(0, query)
        // Keep only last 10 searches
        if (searchHistory.size > 10) {
            searchHistory.removeAt(searchHistory.size - 1)
        }

        saveSearchHistory()
        updateSuggestions()
    }

    private fun setupSuggestions() {
        suggestionsAdapter = SearchSuggestionsAdapter { suggestion ->
            binding.searchEditText.setText(suggestion)
            binding.searchEditText.setSelection(suggestion.length)
            performSearch(suggestion)
        }

        binding.suggestionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SimpleSearchActivity)
            adapter = suggestionsAdapter
        }

        updateSuggestions()
    }

    private fun updateSuggestions() {
        suggestionsAdapter?.submitList(searchHistory.take(5))

        // Show/hide suggestions based on whether we have history and no current search
        val showSuggestions = searchHistory.isNotEmpty() && binding.searchEditText.text.toString().trim().isEmpty()
        binding.suggestionsCard.visibility = if (showSuggestions) View.VISIBLE else View.GONE
    }
}

/**
 * Simple search result data class
 */
data class SimpleSearchResult(
    val pageIndex: Int,
    val pageTitle: String,
    val matchedText: String,
    val context: String,
    val position: Int
)

/**
 * Simple adapter for search results
 */
class SimpleSearchResultsAdapter(
    private val onResultClick: (SimpleSearchResult) -> Unit
) : RecyclerView.Adapter<SimpleSearchResultViewHolder>() {
    
    private var results: List<SimpleSearchResult> = emptyList()
    
    fun submitList(newResults: List<SimpleSearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleSearchResultViewHolder {
        val binding = com.example.epubtranslator.databinding.ItemSearchResultBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return SimpleSearchResultViewHolder(binding, onResultClick)
    }
    
    override fun onBindViewHolder(holder: SimpleSearchResultViewHolder, position: Int) {
        holder.bind(results[position])
    }
    
    override fun getItemCount(): Int = results.size
}

/**
 * Simple ViewHolder for search results
 */
class SimpleSearchResultViewHolder(
    private val binding: com.example.epubtranslator.databinding.ItemSearchResultBinding,
    private val onResultClick: (SimpleSearchResult) -> Unit
) : RecyclerView.ViewHolder(binding.root) {
    
    fun bind(result: SimpleSearchResult) {
        binding.searchResultText.text = Html.fromHtml(result.context, Html.FROM_HTML_MODE_COMPACT)
        binding.searchResultLocation.text = "${result.pageTitle}"

        binding.root.setOnClickListener {
            onResultClick(result)
        }
        
        binding.root.setOnClickListener {
            onResultClick(result)
        }
    }
}

/**
 * Adapter for search suggestions/history
 */
class SearchSuggestionsAdapter(
    private val onSuggestionClick: (String) -> Unit
) : RecyclerView.Adapter<SearchSuggestionViewHolder>() {

    private var suggestions: List<String> = emptyList()

    fun submitList(newSuggestions: List<String>) {
        suggestions = newSuggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SearchSuggestionViewHolder {
        val binding = com.example.epubtranslator.databinding.ItemSearchSuggestionBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return SearchSuggestionViewHolder(binding, onSuggestionClick)
    }

    override fun onBindViewHolder(holder: SearchSuggestionViewHolder, position: Int) {
        holder.bind(suggestions[position])
    }

    override fun getItemCount(): Int = suggestions.size
}

/**
 * ViewHolder for search suggestions
 */
class SearchSuggestionViewHolder(
    private val binding: com.example.epubtranslator.databinding.ItemSearchSuggestionBinding,
    private val onSuggestionClick: (String) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(suggestion: String) {
        binding.suggestionText.text = suggestion

        binding.root.setOnClickListener {
            onSuggestionClick(suggestion)
        }
    }
}
