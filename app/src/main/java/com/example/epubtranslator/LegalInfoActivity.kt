package com.example.epubtranslator

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.example.epubtranslator.databinding.ActivityLegalInfoBinding

class LegalInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLegalInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.legalToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_ABOUT
        binding.titleTextView.text = when (page) {
            PAGE_TERMS -> getString(R.string.terms_title)
            PAGE_PRIVACY -> getString(R.string.privacy_title)
            else -> getString(R.string.about_title)
        }
        binding.dateTextView.text = getString(R.string.legal_last_updated)
        renderArticle(when (page) {
            PAGE_TERMS -> getString(R.string.terms_text)
            PAGE_PRIVACY -> getString(R.string.privacy_text)
            else -> getString(R.string.about_text)
        })
    }

    private fun renderArticle(rawText: String) {
        val lines = rawText
            .replace("&#10;", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .lines()
            .toMutableList()
        if (lines.firstOrNull() == "Terms of Use" || lines.firstOrNull() == "Privacy Policy") {
            lines.removeAt(0)
        }
        while (lines.firstOrNull()?.isNullOrBlank() == true) lines.removeAt(0)
        if (lines.firstOrNull()?.startsWith("Last updated:") == true) lines.removeAt(0)
        while (lines.firstOrNull()?.isNullOrBlank() == true) lines.removeAt(0)

        val sectionHeading = Regex("^(\\d+)\\. (.+)$")
        var currentHeading: String? = null
        val currentBody = mutableListOf<String>()

        fun addSection() {
            val heading = currentHeading ?: return
            addHeading(heading)
            addBody(currentBody)
            currentBody.clear()
        }

        lines.forEach { line ->
            val match = sectionHeading.matchEntire(line.trim())
            if (match != null) {
                addSection()
                currentHeading = "${match.groupValues[1]}. ${match.groupValues[2]}"
            } else {
                currentBody += line
            }
        }
        if (currentHeading != null) {
            addSection()
        } else {
            addBody(lines)
        }
    }

    private fun addHeading(text: String) {
        val heading = TextView(this).apply {
            this.text = text
            setTextColor(resolveThemeColor(android.R.attr.textColorPrimary))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        binding.articleSectionsContainer.addView(heading, sectionParams())
    }

    private fun addBody(lines: List<String>) {
        val paragraphs = lines.joinToString("\n").split(Regex("\\n\\s*\\n"))
        paragraphs.map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
            val body = TextView(this).apply {
                text = paragraph.lines().joinToString("\n") { line ->
                    if (line.trimStart().startsWith("- ")) {
                        line.replaceFirst(Regex("^\\s*- "), "• ")
                    } else {
                        line
                    }
                }
                setTextColor(resolveThemeColor(android.R.attr.textColorSecondary))
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextIsSelectable(true)
            }
            binding.articleSectionsContainer.addView(body, sectionParams(16))
        }
    }

    private fun sectionParams(topMarginDp: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (topMarginDp * resources.displayMetrics.density).toInt()
        }
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) {
            AppCompatResources.getColorStateList(this, value.resourceId).defaultColor
        } else {
            value.data
        }
    }

    companion object {
        const val EXTRA_PAGE = "legal_page"
        const val PAGE_ABOUT = "about"
        const val PAGE_TERMS = "terms"
        const val PAGE_PRIVACY = "privacy"
    }
}
