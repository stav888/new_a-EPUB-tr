package com.example.epubtranslator

import android.animation.ValueAnimator
import android.animation.Animator
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var isInitialisingLanguage = true
    private var selectedLanguage: Language = Language.ENGLISH
    private var progressAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        languageManager = LanguageManager(this)
        mlKitOfflineManager = MlKitOfflineManager(this)

        // Set up the language spinner
        setupLanguageSpinner()

        // Set up save button
        binding.saveButton.setOnClickListener {
            saveSettings()
        }
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
        selectedLanguage = currentLanguage
        val position = languages.indexOfFirst { it.code == currentLanguage.code }
        if (position != -1) {
            binding.languageSpinner.setSelection(position)
        }
        binding.languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit

            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedLanguage = languages[position]
                if (isInitialisingLanguage) {
                    isInitialisingLanguage = false
                } else {
                    selectLanguageForDownload(selectedLanguage)
                }
            }
        }
        binding.languageSpinner.post { refreshSelectedLanguageStatus(selectedLanguage) }
    }

    private fun refreshSelectedLanguageStatus(language: Language) {
        binding.downloadStatusText.text = getString(R.string.checking_model, language.displayName)
        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.isIndeterminate = true
        binding.downloadProgress.progress = 0
        binding.downloadStatusText.visibility = View.VISIBLE

        coroutineScope.launch {
            val isDownloaded = mlKitOfflineManager.isLanguageDownloaded(language)
            if (isDownloaded) {
                showDownloadedState(language)
            } else {
                binding.downloadProgress.visibility = View.GONE
                binding.downloadStatusText.text = getString(R.string.select_target_language_to_download)
            }
        }
    }

    private fun downloadSelectedLanguage(language: Language) {
        binding.downloadStatusText.text = getString(R.string.download_in_progress_for, language.displayName)
        binding.downloadProgress.isIndeterminate = false
        binding.downloadProgress.progress = 0
        mlKitOfflineManager.downloadLanguage(
            language,
            progressCallback = { progress ->
                runOnUiThread { animateProgressTo(progress.coerceAtMost(90)) }
            },
            completionCallback = { success ->
                if (success) {
                    animateProgressTo(100) {
                        showDownloadedState(language)
                    }
                } else {
                    progressAnimator?.cancel()
                    binding.downloadProgress.visibility = View.GONE
                    binding.downloadStatusText.text = getString(R.string.model_download_failed)
                }
            }
        )
    }

    private fun selectLanguageForDownload(language: Language) {
        binding.downloadStatusText.text = getString(R.string.checking_model, language.displayName)
        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.isIndeterminate = true

        coroutineScope.launch {
            if (mlKitOfflineManager.isLanguageDownloaded(language)) {
                showDownloadedState(language)
            } else {
                downloadSelectedLanguage(language)
            }
        }
    }

    private fun animateProgressTo(target: Int, onComplete: (() -> Unit)? = null) {
        progressAnimator?.cancel()
        val start = binding.downloadProgress.progress
        progressAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = if (target >= 100) 450L else 1200L
            addUpdateListener { animator ->
                binding.downloadProgress.progress = animator.animatedValue as Int
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationEnd(animation: Animator) {
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    private fun showDownloadedState(language: Language) {
        binding.downloadProgress.visibility = View.GONE
        binding.downloadStatusText.text = getString(R.string.model_downloaded_for, language.displayName)
    }

    private fun saveSettings() {
        // Get the selected language
        // Save the selected language
        languageManager.setTargetLanguage(selectedLanguage)

        // Show confirmation and finish
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        progressAnimator?.cancel()
        mlKitOfflineManager.onDestroy()
    }
}
