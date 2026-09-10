package com.example.epubtranslator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.epubtranslator.translation.OfflineLanguage

class LanguageAdapter(
    private val languages: List<OfflineLanguage>,
    private val onDownloadClick: (OfflineLanguage) -> Unit,
    private val onDeleteClick: (OfflineLanguage) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    class LanguageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val languageName: TextView = view.findViewById(R.id.languageName)
        val downloadButton: ImageButton = view.findViewById(R.id.downloadButton)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        val downloadProgress: ProgressBar = view.findViewById(R.id.downloadProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_language, parent, false)
        return LanguageViewHolder(view)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        val language = languages[position]
        holder.languageName.text = language.name

        // Set button visibility based on download state
        if (language.isDownloaded) {
            holder.downloadButton.visibility = View.GONE
            holder.deleteButton.visibility = View.VISIBLE
        } else {
            holder.downloadButton.visibility = View.VISIBLE
            holder.deleteButton.visibility = View.GONE
        }

        // Show/hide progress bar based on download state
        if (language.isDownloading) {
            holder.downloadProgress.visibility = View.VISIBLE
            holder.downloadProgress.progress = language.downloadProgress
        } else {
            holder.downloadProgress.visibility = View.GONE
        }

        // Set click listeners
        holder.downloadButton.setOnClickListener {
            onDownloadClick(language)
        }

        holder.deleteButton.setOnClickListener {
            onDeleteClick(language)
        }
    }

    override fun getItemCount() = languages.size

    // Method to update a language's download progress
    fun updateDownloadProgress(languageCode: String, progress: Int) {
        val index = languages.indexOfFirst { it.code == languageCode }
        if (index != -1) {
            languages[index].downloadProgress = progress
            notifyItemChanged(index)
        }
    }

    // Method to update a language's download state
    fun updateDownloadState(languageCode: String, isDownloading: Boolean, isDownloaded: Boolean) {
        val index = languages.indexOfFirst { it.code == languageCode }
        if (index != -1) {
            languages[index].isDownloading = isDownloading
            languages[index].isDownloaded = isDownloaded
            notifyItemChanged(index)
        }
    }
}
