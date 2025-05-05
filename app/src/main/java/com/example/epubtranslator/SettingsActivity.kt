package com.example.epubtranslator

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.epubtranslator.databinding.ActivitySettingsBinding
import com.example.epubtranslator.translation.Language
import com.example.epubtranslator.translation.LanguageManager

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var languageManager: LanguageManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize language manager
        languageManager = LanguageManager(this)
        
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
        val position = languages.indexOfFirst { it.code == currentLanguage.code }
        if (position != -1) {
            binding.languageSpinner.setSelection(position)
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
}
