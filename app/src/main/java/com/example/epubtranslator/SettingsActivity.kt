package com.example.epubtranslator

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.epubtranslator.databinding.ActivitySettingsBinding
import com.example.epubtranslator.translation.Language
import com.example.epubtranslator.translation.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var languageManager: LanguageManager
    private lateinit var mlKitOfflineManager: MlKitOfflineManager
    private lateinit var languageAdapter: LanguageAdapter
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        languageManager = LanguageManager(this)
        mlKitOfflineManager = MlKitOfflineManager(this)

        // Set up the language spinner
        setupLanguageSpinner()

        // Set up offline models RecyclerView
        setupOfflineLanguagesRecyclerView()

        // Set up save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }

        // Refresh offline model status on open
        refreshOfflineModelStatus()
    }

    private fun setupLanguageSpinner() {
        // Get all available languages
        val languages = Language.values()

        // Create adapter for spinner
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter

        // Set the current selected language
        val currentLanguage = languageManager.getTargetLanguage()
        val position = languages.indexOfFirst { it.code == currentLanguage.code }
        if (position != -1) {
            binding.languageSpinner.setSelection(position)
        }
    }

    private fun setupOfflineLanguagesRecyclerView() {
        val offlineLanguages = mlKitOfflineManager.getSupportedLanguages()

        languageAdapter = LanguageAdapter(
            offlineLanguages,
            onDownloadClick = { language ->
                downloadLanguage(language)
            },
            onDeleteClick = { language ->
                deleteLanguage(language)
            }
        )

        binding.offlineLanguagesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.offlineLanguagesRecyclerView.adapter = languageAdapter
    }

    private fun downloadLanguage(language: com.example.epubtranslator.translation.OfflineLanguage) {
        mlKitOfflineManager.downloadLanguage(
            language,
            progressCallback = { progress ->
                languageAdapter.updateDownloadProgress(language.code, progress)
            },
            completionCallback = { success ->
                if (success) {
                    Toast.makeText(this, "Downloaded ${language.name}", Toast.LENGTH_SHORT).show()
                    languageAdapter.updateDownloadState(language.code, false, true)
                } else {
                    Toast.makeText(this, "Failed to download ${language.name}", Toast.LENGTH_SHORT).show()
                    languageAdapter.updateDownloadState(language.code, false, false)
                }
            }
        )
        languageAdapter.updateDownloadState(language.code, true, false)
    }

    private fun deleteLanguage(language: com.example.epubtranslator.translation.OfflineLanguage) {
        mlKitOfflineManager.deleteLanguage(
            language,
            completionCallback = { success ->
                if (success) {
                    Toast.makeText(this, "Deleted ${language.name}", Toast.LENGTH_SHORT).show()
                    languageAdapter.updateDownloadState(language.code, false, false)
                } else {
                    Toast.makeText(this, "Failed to delete ${language.name}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun refreshOfflineModelStatus() {
        coroutineScope.launch {
            try {
                val offlineLanguages = mlKitOfflineManager.getSupportedLanguages()
                for (language in offlineLanguages) {
                    languageAdapter.updateDownloadState(language.code, false, language.isDownloaded)
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Error refreshing offline status", e)
            }
        }
    }

    private fun saveSettings() {
        // Get the selected language
        val selectedPosition = binding.languageSpinner.selectedItemPosition
        val selectedLanguage = Language.values()[selectedPosition]

        // Save the selected language
        languageManager.setTargetLanguage(selectedLanguage)

        // Show confirmation and finish
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        mlKitOfflineManager.onDestroy()
    }
}
