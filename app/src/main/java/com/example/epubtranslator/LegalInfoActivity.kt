package com.example.epubtranslator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.epubtranslator.databinding.ActivityLegalInfoBinding

class LegalInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLegalInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_ABOUT
        binding.titleTextView.text = when (page) {
            PAGE_TERMS -> getString(R.string.terms_title)
            PAGE_PRIVACY -> getString(R.string.privacy_title)
            else -> getString(R.string.about)
        }
        binding.bodyTextView.text = when (page) {
            PAGE_TERMS -> getString(R.string.terms_content)
            PAGE_PRIVACY -> getString(R.string.privacy_content)
            else -> getString(R.string.about_text)
        }
    }

    companion object {
        const val EXTRA_PAGE = "legal_page"
        const val PAGE_ABOUT = "about"
        const val PAGE_TERMS = "terms"
        const val PAGE_PRIVACY = "privacy"
    }
}
