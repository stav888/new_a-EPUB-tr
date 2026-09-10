package com.example.epubtranslator.translation

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Toast
import com.example.epubtranslator.R
import com.example.epubtranslator.SettingsActivity
import com.example.epubtranslator.databinding.DialogTranslationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog to display translation results
 */
class TranslationDialog(
    context: Context,
    private val originalText: String,
    private val translationManager: TranslationManager
) : Dialog(context), CoroutineScope by MainScope() {

    private lateinit var binding: DialogTranslationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Inflate the layout
        binding = DialogTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set dialog width to 90% of screen width
        val window = window
        window?.let {
            val width = (context.resources.displayMetrics.widthPixels * 0.9).toInt()
            it.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }

        // Set the original text
        binding.originalTextView.text = originalText

        // Set up close button
        binding.closeButton.setOnClickListener {
            dismiss()
        }

        // Set up open settings button
        binding.openSettingsButton.setOnClickListener {
            val intent = Intent(context, SettingsActivity::class.java)
            context.startActivity(intent)
            dismiss()
        }

        // Show loading indicator
        binding.loadingProgressBar.visibility = View.VISIBLE
        binding.translationTextView.visibility = View.GONE
        binding.openSettingsButton.visibility = View.GONE

        // Get the translation
        translateText()
    }

    private fun translateText() {
        // Use the dialog's own coroutine scope instead of the activity's
        launch {
            try {
                // Show loading state
                binding.loadingProgressBar.visibility = View.VISIBLE
                binding.translationTextView.visibility = View.GONE

                // Show a toast to indicate translation is in progress
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Translating...", Toast.LENGTH_SHORT).show()
                }

                // Get translation
                val result = translationManager.translateText(originalText)

                // Update UI with result on the main thread
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.visibility = View.GONE
                    binding.translationTextView.visibility = View.VISIBLE

                    result.fold(
                        onSuccess = { translatedText ->
                            binding.translationTextView.text = translatedText
                            binding.openSettingsButton.visibility = View.GONE
                            Toast.makeText(context, "Translation completed", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            // Check if error is ModelNotDownloadedException
                            if (error is ModelNotDownloadedException) {
                                val errorMessage = context.getString(R.string.offline_model_not_downloaded)
                                binding.translationTextView.text = errorMessage
                                binding.openSettingsButton.visibility = View.VISIBLE
                                Toast.makeText(context, "Please download offline models", Toast.LENGTH_LONG).show()
                            } else {
                                val errorMessage = "${context.getString(R.string.error_translation)}\n\nError: ${error.message}"
                                binding.translationTextView.text = errorMessage
                                binding.openSettingsButton.visibility = View.GONE
                                error.printStackTrace()
                                Toast.makeText(context, "Translation error: ${error.message}", Toast.LENGTH_LONG).show()

                                // Log the error for debugging
                                android.util.Log.e("TranslationDialog", "Translation error", error)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                // Handle errors on the main thread
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.visibility = View.GONE
                    binding.translationTextView.visibility = View.VISIBLE

                    if (e is ModelNotDownloadedException) {
                        val errorMessage = context.getString(R.string.offline_model_not_downloaded)
                        binding.translationTextView.text = errorMessage
                        binding.openSettingsButton.visibility = View.VISIBLE
                        Toast.makeText(context, "Please download offline models", Toast.LENGTH_LONG).show()
                    } else {
                        val errorMessage = "${context.getString(R.string.error_translation)}\n\nException: ${e.message}"
                        binding.translationTextView.text = errorMessage
                        binding.openSettingsButton.visibility = View.GONE
                        e.printStackTrace()
                        Toast.makeText(context, "Exception: ${e.message}", Toast.LENGTH_LONG).show()

                        // Log the exception for debugging
                        android.util.Log.e("TranslationDialog", "Exception in translation", e)
                    }
                }
            }
        }
    }

    private var isCancelled = false

    override fun dismiss() {
        if (!isCancelled) {
            // Cancel all coroutines when the dialog is dismissed
            isCancelled = true
            cancel()
        }
        super.dismiss()
    }

    override fun onDetachedFromWindow() {
        // Cancel all coroutines when the dialog is detached
        if (!isCancelled) {
            isCancelled = true
            cancel()
        }
        super.onDetachedFromWindow()
    }
}
