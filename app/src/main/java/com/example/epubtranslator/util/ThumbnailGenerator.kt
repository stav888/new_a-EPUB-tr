package com.example.epubtranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class for generating thumbnails for EPUB files
 */
class ThumbnailGenerator(private val context: Context) {
    
    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val THUMBNAIL_WIDTH = 200
        private const val THUMBNAIL_HEIGHT = 300
        private const val THUMBNAILS_DIR = "thumbnails"
    }
    
    /**
     * Generate a thumbnail for an EPUB file
     * 
     * @param epubFilePath Path to the EPUB file
     * @return Path to the generated thumbnail, or null if generation failed
     */
    suspend fun generateThumbnail(epubFilePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(epubFilePath)
            if (!file.exists()) {
                Log.e(TAG, "EPUB file does not exist: $epubFilePath")
                return@withContext null
            }
            
            // Create thumbnails directory if it doesn't exist
            val thumbnailsDir = File(context.filesDir, THUMBNAILS_DIR)
            if (!thumbnailsDir.exists()) {
                thumbnailsDir.mkdirs()
            }
            
            // Generate a unique filename for the thumbnail
            val thumbnailFileName = "${file.nameWithoutExtension}_${file.lastModified()}.png"
            val thumbnailFile = File(thumbnailsDir, thumbnailFileName)
            
            // Check if thumbnail already exists
            if (thumbnailFile.exists()) {
                return@withContext thumbnailFile.absolutePath
            }
            
            // Try to extract cover image from EPUB
            val coverBitmap = extractCoverFromEpub(epubFilePath)
                ?: generateDefaultCover(file.nameWithoutExtension)
            
            // Save the bitmap to a file
            FileOutputStream(thumbnailFile).use { out ->
                coverBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            return@withContext thumbnailFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail", e)
            return@withContext null
        }
    }
    
    /**
     * Extract the cover image from an EPUB file
     */
    private fun extractCoverFromEpub(epubFilePath: String): Bitmap? {
        try {
            val zipFile = ZipFile(epubFilePath)
            val entries = zipFile.entries()
            
            // Look for common cover image filenames
            val coverPatterns = listOf(
                "cover.jpg", "cover.jpeg", "cover.png",
                "Cover.jpg", "Cover.jpeg", "Cover.png",
                "cover_image", "coverimage"
            )
            
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name.lowercase()
                
                // Check if this entry might be a cover image
                if (!entry.isDirectory && 
                    (entryName.endsWith(".jpg") || entryName.endsWith(".jpeg") || entryName.endsWith(".png")) &&
                    (coverPatterns.any { entryName.contains(it.lowercase()) } || entryName.contains("cover"))
                ) {
                    // Try to decode the image
                    val inputStream = zipFile.getInputStream(entry)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    
                    if (bitmap != null) {
                        // Resize the bitmap to the desired thumbnail size
                        return resizeBitmap(bitmap, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
                    }
                }
            }
            
            zipFile.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cover from EPUB", e)
        }
        
        return null
    }
    
    /**
     * Generate a default cover image with the book title
     */
    private fun generateDefaultCover(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Fill the background with a gradient
        val paint = Paint()
        paint.color = Color.rgb(63, 81, 181) // Primary color
        canvas.drawRect(0f, 0f, THUMBNAIL_WIDTH.toFloat(), THUMBNAIL_HEIGHT.toFloat(), paint)
        
        // Draw the title text
        paint.color = Color.WHITE
        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        
        // Wrap the text if it's too long
        val lines = wrapText(title, paint, THUMBNAIL_WIDTH - 20)
        
        // Draw each line of text
        val lineHeight = paint.fontSpacing
        val startY = (THUMBNAIL_HEIGHT - (lines.size * lineHeight)) / 2 + lineHeight
        
        for ((i, line) in lines.withIndex()) {
            canvas.drawText(line, THUMBNAIL_WIDTH / 2f, startY + i * lineHeight, paint)
        }
        
        return bitmap
    }
    
    /**
     * Resize a bitmap to the specified dimensions
     */
    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val scaledBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBitmap)
        
        // Calculate scaling to maintain aspect ratio
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        
        val sourceRect = Rect(0, 0, sourceWidth, sourceHeight)
        val destRect: Rect
        
        if (sourceWidth / sourceHeight > width / height) {
            // Source is wider than destination
            val scaledHeight = sourceHeight * width / sourceWidth
            val yOffset = (height - scaledHeight) / 2
            destRect = Rect(0, yOffset, width, yOffset + scaledHeight)
        } else {
            // Source is taller than destination
            val scaledWidth = sourceWidth * height / sourceHeight
            val xOffset = (width - scaledWidth) / 2
            destRect = Rect(xOffset, 0, xOffset + scaledWidth, height)
        }
        
        canvas.drawBitmap(bitmap, sourceRect, destRect, null)
        return scaledBitmap
    }
    
    /**
     * Wrap text to fit within the specified width
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        val lines = mutableListOf<String>()
        val words = text.split(" ")
        
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = paint.measureText(testLine)
            
            if (testWidth <= maxWidth) {
                currentLine = testLine
            } else {
                lines.add(currentLine)
                currentLine = word
            }
        }
        
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        
        // Limit to 3 lines
        if (lines.size > 3) {
            val truncatedLines = lines.take(2).toMutableList()
            truncatedLines.add("${lines[2]}...")
            return truncatedLines
        }
        
        return lines
    }
}
