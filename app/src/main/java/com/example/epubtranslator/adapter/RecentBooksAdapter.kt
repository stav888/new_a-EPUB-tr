package com.example.epubtranslator.adapter

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.example.epubtranslator.R
import com.example.epubtranslator.data.RecentBook
import com.example.epubtranslator.databinding.ItemRecentBookBinding
import com.example.epubtranslator.databinding.ItemRecentBookGridBinding
import java.io.File

class RecentBooksAdapter(
    private var books: List<RecentBook>,
    private var viewMode: Int = VIEW_MODE_LIST,
    private val onBookClick: (RecentBook) -> Unit,
    private val onBookLongClick: (View, RecentBook) -> Boolean = { _, _ -> false }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    companion object {
        const val VIEW_MODE_LIST = 0
        const val VIEW_MODE_GRID = 1
        private const val TAG = "RecentBooksAdapter"
    }

    override fun getItemViewType(position: Int): Int = viewMode

    override fun getItemId(position: Int): Long {
        // Use filePath as a stable unique key
        return books[position].filePath.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_MODE_LIST -> {
                val binding = ItemRecentBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                ListViewHolder(binding)
            }
            VIEW_MODE_GRID -> {
                val binding = ItemRecentBookGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
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

    inner class ListViewHolder(private val binding: ItemRecentBookBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }
            binding.bookThumbnailImageView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }
            // Long-press specifically on the cover image to show context menu
            binding.bookThumbnailImageView.setOnLongClickListener { v ->
                val position = adapterPosition
                Log.d(TAG, "Long-press detected on LIST view at position: $position")
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "Calling onBookLongClick for book: ${books[position].title}")
                    onBookLongClick(v, books[position])
                } else {
                    Log.w(TAG, "Long-press detected but position is NO_POSITION")
                    false
                }
            }

            // Also add long-press to the entire item for better UX
            binding.root.setOnLongClickListener { v ->
                val position = adapterPosition
                Log.d(TAG, "Long-press detected on LIST root at position: $position")
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "Calling onBookLongClick for book: ${books[position].title}")
                    onBookLongClick(v, books[position])
                } else {
                    Log.w(TAG, "Long-press detected but position is NO_POSITION")
                    false
                }
            }
        }

        fun bind(book: RecentBook) {
            Log.d(TAG, "Binding LIST book: ${book.title}, coverPath: ${book.coverImagePath}")

            binding.bookTitleTextView.text = book.title
            binding.bookFileNameTextView.text = book.getFileName()
            binding.lastOpenedTextView.text = binding.root.context.getString(R.string.last_opened, book.getFormattedDate())

            // Load cover image with Glide for better performance and caching
            loadCoverImageWithGlide(book.coverImagePath, binding.bookThumbnailImageView, false)
        }
    }

    inner class GridViewHolder(private val binding: ItemRecentBookGridBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }
            binding.bookThumbnailImageView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onBookClick(books[position])
                }
            }
            // Long-press specifically on the cover image to show context menu
            binding.bookThumbnailImageView.setOnLongClickListener { v ->
                val position = adapterPosition
                Log.d(TAG, "Long-press detected on GRID view at position: $position")
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "Calling onBookLongClick for book: ${books[position].title}")
                    onBookLongClick(v, books[position])
                } else {
                    Log.w(TAG, "Long-press detected but position is NO_POSITION")
                    false
                }
            }

            // Also add long-press to the entire item for better UX
            binding.root.setOnLongClickListener { v ->
                val position = adapterPosition
                Log.d(TAG, "Long-press detected on GRID root at position: $position")
                if (position != RecyclerView.NO_POSITION) {
                    Log.d(TAG, "Calling onBookLongClick for book: ${books[position].title}")
                    onBookLongClick(v, books[position])
                } else {
                    Log.w(TAG, "Long-press detected but position is NO_POSITION")
                    false
                }
            }
        }

        fun bind(book: RecentBook) {
            Log.d(TAG, "Binding GRID book: ${book.title}, coverPath: ${book.coverImagePath}")

            binding.bookTitleTextView.text = book.title
            binding.bookFileNameTextView.text = book.getFileName()
            binding.lastOpenedTextView.text = binding.root.context.getString(R.string.last_opened, book.getFormattedDate())

            // Load cover image with Glide for better performance and caching
            loadCoverImageWithGlide(book.coverImagePath, binding.bookThumbnailImageView, true)
        }
    }

    private fun loadCoverImageWithGlide(coverPath: String?, imageView: android.widget.ImageView, isGrid: Boolean) {
        val context = imageView.context

        // Enable caching for better performance and user experience
        val base = RequestOptions()
            .placeholder(R.drawable.ic_book_placeholder)
            .error(R.drawable.ic_book_placeholder)
            .fallback(R.drawable.ic_book_placeholder)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC) // Enable smart disk caching
            .skipMemoryCache(false) // Enable memory cache for better performance

        // Use fitCenter for better aspect ratio preservation, centerCrop for grid view
        val requestOptions = if (isGrid) {
            base.centerCrop()
        } else {
            base.fitCenter()
        }

        if (coverPath.isNullOrEmpty()) {
            Log.w(TAG, "Cover path is null or empty, using placeholder")
            Glide.with(context).load(R.drawable.ic_book_placeholder).apply(requestOptions).into(imageView)
            return
        }

        val coverFile = File(coverPath)
        if (!coverFile.exists() || !coverFile.canRead()) {
            Log.w(TAG, "Cover file missing or unreadable: $coverPath")
            Glide.with(context).load(R.drawable.ic_book_placeholder).apply(requestOptions).into(imageView)
            return
        }

        // Load the cover image with proper error handling
        Glide.with(context)
            .load(coverFile)
            .signature(ObjectKey("${coverFile.lastModified()}_${coverFile.length()}")) // Better cache key
            .apply(requestOptions)
            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                    Log.e(TAG, "Failed to load cover: $coverPath", e)
                    // Let Glide handle the error by showing the error drawable
                    return false
                }

                override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                    Log.d(TAG, "Successfully loaded cover: $coverPath")
                    return false
                }
            })
            .into(imageView)
    }
}
