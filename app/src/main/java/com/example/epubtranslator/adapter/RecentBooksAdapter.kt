package com.example.epubtranslator.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.epubtranslator.R
import com.example.epubtranslator.data.RecentBook
import com.example.epubtranslator.databinding.ItemRecentBookBinding
import com.example.epubtranslator.databinding.ItemRecentBookGridBinding
import java.io.File

class RecentBooksAdapter(
    private var books: List<RecentBook>,
    private var viewMode: Int = VIEW_MODE_LIST,
    private val onBookClick: (RecentBook) -> Unit,
    private val onBookLongClick: (RecentBook) -> Boolean = { false }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_MODE_LIST = 0
        const val VIEW_MODE_GRID = 1
    }

    override fun getItemViewType(position: Int): Int {
        return viewMode
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_MODE_LIST -> {
                val binding = ItemRecentBookBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                ListViewHolder(binding)
            }
            VIEW_MODE_GRID -> {
                val binding = ItemRecentBookGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                GridViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val book = books[position]
        when (holder) {
            is ListViewHolder -> holder.bind(book)
            is GridViewHolder -> holder.bind(book)
        }
    }

    override fun getItemCount(): Int = books.size

    fun updateBooks(newBooks: List<RecentBook>) {
        books = newBooks
        notifyDataSetChanged()
    }

    fun setViewMode(newViewMode: Int) {
        if (viewMode != newViewMode) {
            viewMode = newViewMode
            notifyDataSetChanged()
        }
    }

    inner class ListViewHolder(private val binding: ItemRecentBookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }

            binding.root.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookLongClick(books[position])
                } else {
                    false
                }
            }
        }

        fun bind(book: RecentBook) {
            // Set book title and filename
            binding.bookTitleTextView.text = book.title
            binding.bookFileNameTextView.text = book.getFileName()

            // Set last opened text
            val lastOpenedText = binding.root.context.getString(R.string.last_opened, book.getFormattedDate())
            binding.lastOpenedTextView.text = lastOpenedText

            // Load thumbnail if available
            if (book.coverImagePath != null && File(book.coverImagePath).exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(book.coverImagePath)
                    binding.bookThumbnailImageView.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    // If loading fails, keep the default background
                    e.printStackTrace()
                }
            }
        }
    }

    inner class GridViewHolder(private val binding: ItemRecentBookGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }

            binding.root.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookLongClick(books[position])
                } else {
                    false
                }
            }
        }

        fun bind(book: RecentBook) {
            // Set book title
            binding.bookTitleTextView.text = book.title

            // Set last opened text
            val lastOpenedText = binding.root.context.getString(R.string.last_opened, book.getFormattedDate())
            binding.lastOpenedTextView.text = lastOpenedText

            // Load thumbnail if available
            if (book.coverImagePath != null && File(book.coverImagePath).exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(book.coverImagePath)
                    binding.bookThumbnailImageView.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    // If loading fails, keep the default background
                    e.printStackTrace()
                }
            }
        }
    }
}
