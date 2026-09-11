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

class TranslationDialog(
    context: Context,
    private val originalText: String,
    private val translationManager: TranslationManager,
    private val onShowInBook: (String) -> Unit,
    private val onTranslationFailed: () -> Unit = {}
) : Dialog(context), CoroutineScope by MainScope() {

    private lateinit var binding: DialogTranslationBinding
    private var translatedText: String? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.let {
            it.setLayout((context.resources.displayMetrics.widthPixels * 0.9).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            it.setBackgroundDrawableResource(android.R.color.transparent)
        }

        binding.originalTextView.text = originalText
        binding.translationTextView.visibility = View.GONE
        binding.translationTitleTextView.visibility = View.GONE
        binding.attributionTextView.visibility = View.GONE
        binding.showInBookButton.visibility = View.GONE
        binding.openSettingsButton.visibility = View.GONE

        binding.closeButton.setOnClickListener { dismiss() }
        binding.showInBookButton.setOnClickListener {
            translatedText?.let(onShowInBook)
            dismiss()
        }
        binding.openSettingsButton.setOnClickListener {
            context.startActivity(Intent(context, SettingsActivity::class.java))
            dismiss()
        }

        translateText()
    }

    private fun translateText() {
        launch {
            try {
                val result = translationManager.translateText(originalText)
                withContext(Dispatchers.Main) {
                    binding.loadingProgressBar.visibility = View.GONE
                    result.fold(
                        onSuccess = { value ->
                            translatedText = value
                            binding.translationTextView.text = value
                            binding.translationTextView.visibility = View.VISIBLE
                            binding.translationTitleTextView.visibility = View.VISIBLE
                            binding.attributionTextView.visibility = View.VISIBLE
                            binding.showInBookButton.visibility = View.VISIBLE
                        },
                        onFailure = { error ->
                            onTranslationFailed()
                            binding.translationTextView.text = if (error is ModelNotDownloadedException) {
                                binding.openSettingsButton.visibility = View.VISIBLE
                                context.getString(R.string.offline_model_not_downloaded)
                            } else {
                                "${context.getString(R.string.error_translation)}\n\n${error.message}"
                            }
                            binding.translationTextView.visibility = View.VISIBLE
                        }
                    )
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    onTranslationFailed()
                    binding.loadingProgressBar.visibility = View.GONE
                    binding.translationTextView.text = "${context.getString(R.string.error_translation)}\n\n${error.message}"
                    binding.translationTextView.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun dismiss() {
        if (!closed) {
            closed = true
            cancel()
        }
        super.dismiss()
    }
}
