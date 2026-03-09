package com.example.epubtranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ThumbnailGenerator(private val context: Context) {
    companion object {
        private const val TAG = "ThumbnailGenerator"
        private const val THUMBNAIL_WIDTH = 200
        private const val THUMBNAIL_HEIGHT = 300
        private const val THUMBNAILS_DIR = "thumbnails"
        // If an existing thumbnail is smaller than this, treat it as legacy/simple and regenerate
        private const val MIN_VALID_THUMB_SIZE_BYTES = 8 * 1024 // 8KB heuristic
    }

    suspend fun generateThumbnail(epubFilePath: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Generating thumbnail for: $epubFilePath")
            val file = File(epubFilePath)
            if (!file.exists()) {
                Log.e(TAG, "EPUB file not found: $epubFilePath")
                return@withContext null
            }
            val thumbnailsDir = File(context.filesDir, THUMBNAILS_DIR).apply { mkdirs() }
            val thumbnailFileName = "${file.nameWithoutExtension}_thumb.jpg"
            val thumbnailFile = File(thumbnailsDir, thumbnailFileName)

            val needRegeneration = if (thumbnailFile.exists() && thumbnailFile.length() > 0) {
                val size = thumbnailFile.length()
                val regenerate = size < MIN_VALID_THUMB_SIZE_BYTES
                Log.d(TAG, "Existing thumbnail found size=${size}B regenerate=$regenerate path=${thumbnailFile.absolutePath}")
                regenerate
            } else false

            if (thumbnailFile.exists() && !needRegeneration) {
                Log.d(TAG, "Reusing existing thumbnail: ${thumbnailFile.absolutePath}")
                return@withContext thumbnailFile.absolutePath
            }

            // Try to extract real cover first; if fails fall back to simple cover
            val coverBitmap = extractCoverBitmap(file) ?: createSimpleCover(file.nameWithoutExtension)

            FileOutputStream(thumbnailFile).use { out ->
                // Use JPEG for better compression and smaller file sizes
                coverBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d(TAG, "Thumbnail saved to: ${thumbnailFile.absolutePath}")
            return@withContext thumbnailFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
            return@withContext null
        }
    }

    private fun extractCoverBitmap(epubFile: File): Bitmap? {
        var zipFile: ZipFile? = null
        return try {
            zipFile = ZipFile(epubFile)
            val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: run {
                Log.w(TAG, "container.xml not found")
                return null
            }
            val containerDoc = zipFile.getInputStream(containerEntry).use { Jsoup.parse(it, "UTF-8", "") }
            val rootfilePath = containerDoc.selectFirst("rootfile")?.attr("full-path")
            if (rootfilePath.isNullOrBlank()) {
                Log.w(TAG, "rootfile path missing in container.xml")
                return null
            }
            val opfDir = File(rootfilePath).parent?.let { if (it == ".") "" else it } ?: ""
            val opfEntry = zipFile.getEntry(rootfilePath) ?: run {
                Log.w(TAG, "OPF file $rootfilePath not found in epub")
                return null
            }
            val opfDoc = zipFile.getInputStream(opfEntry).use { Jsoup.parse(it, "UTF-8", "") }

            // Strategy 1: meta name="cover" content="id"
            var coverId: String? = opfDoc.select("meta[name=cover]").firstOrNull()?.attr("content")
            // Strategy 2: meta property="cover-image"
            if (coverId.isNullOrBlank()) {
                coverId = opfDoc.select("meta[property=cover-image]").firstOrNull()?.attr("content")
            }

            var coverHref: String? = null
            if (!coverId.isNullOrBlank()) {
                coverHref = opfDoc.select("manifest item[id=$coverId]").firstOrNull()?.attr("href")
            }
            // Strategy 3: any manifest item id or href containing 'cover' and image media-type
            if (coverHref.isNullOrBlank()) {
                val candidate = opfDoc.select("manifest item").firstOrNull { el ->
                    val media = el.attr("media-type")
                    val idAttr = el.attr("id").lowercase()
                    val hrefAttr = el.attr("href").lowercase()
                    media.startsWith("image/") && ("cover" in idAttr || "cover" in hrefAttr)
                }
                coverHref = candidate?.attr("href")
            }
            if (coverHref.isNullOrBlank()) {
                Log.w(TAG, "No cover href found in OPF; falling back")
                return null
            }
            val normalizedPath = if (opfDir.isNotEmpty()) "$opfDir/$coverHref" else coverHref
            val coverEntry: ZipEntry = zipFile.getEntry(normalizedPath) ?: run {
                Log.w(TAG, "Cover image entry $normalizedPath not found in zip")
                return null
            }
            val bytes = zipFile.getInputStream(coverEntry).use(InputStream::readBytes)
            val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                Log.w(TAG, "BitmapFactory failed to decode cover bytes")
                return null
            }
            Log.d(TAG, "Extracted raw cover bitmap ${rawBitmap.width}x${rawBitmap.height}")
            return scaleAndLetterbox(rawBitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Cover extraction failed: ${e.message}")
            null
        } finally {
            try { zipFile?.close() } catch (_: Exception) {}
        }
    }

    private fun scaleAndLetterbox(source: Bitmap): Bitmap {
        val targetW = THUMBNAIL_WIDTH
        val targetH = THUMBNAIL_HEIGHT
        val srcW = source.width
        val srcH = source.height
        val scale = minOf(targetW / srcW.toFloat(), targetH / srcH.toFloat())
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.DKGRAY)
        val left = (targetW - scaledW) / 2f
        val top = (targetH - scaledH) / 2f
        canvas.drawBitmap(scaled, left, top, null)
        if (scaled != source) scaled.recycle()
        return output
    }

    private fun createSimpleCover(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(63, 81, 181))
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val words = title.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= THUMBNAIL_WIDTH - 40) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
                if (lines.size >= 3) break
            }
        }
        if (currentLine.isNotEmpty() && lines.size < 3) lines.add(currentLine)
        val lineHeight = paint.fontSpacing
        val startY = (THUMBNAIL_HEIGHT - (lines.size * lineHeight)) / 2 + lineHeight
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, THUMBNAIL_WIDTH / 2f, startY + index * lineHeight, paint)
        }
        return bitmap
    }
}
