package com.example.epubtranslator.logging

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Logger class that sends logs to a Google Sheet
 */
class GoogleSheetsLogger(private val context: Context) {
    companion object {
        private const val TAG = "GoogleSheetsLogger"
        
        // Google Sheets script URL - this is the URL to the Google Apps Script Web App
        private const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbwQXvUw9RPXuGxkE_QNRXjzwxPJTLyhJWHn-OZP-_LO5EZj-_Iy-_Hs-Ij-Iy-_LO5EZj/exec"
        
        // Sheet ID from the URL
        private const val SHEET_ID = "1YDffUPG313HiAJEkTHZhXe-ovZY4x9GU-JWwjbhJX0A"
    }
    
    // Generate a unique session ID for this app session
    private val sessionId = UUID.randomUUID().toString().substring(0, 8)
    
    // Get device information
    private val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    
    /**
     * Log a translation attempt
     */
    suspend fun logTranslationAttempt(
        sourceText: String,
        sourceLanguage: String,
        targetLanguage: String,
        isOffline: Boolean,
        isSuccessful: Boolean,
        translatedText: String?,
        errorMessage: String?
    ) = withContext(Dispatchers.IO) {
        try {
            // Format the current timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            // Prepare the data to send
            val postData = mapOf(
                "action" to "logTranslation",
                "sheetId" to SHEET_ID,
                "timestamp" to timestamp,
                "sessionId" to sessionId,
                "deviceInfo" to deviceInfo,
                "sourceText" to sourceText.take(100), // Limit text length
                "sourceLanguage" to sourceLanguage,
                "targetLanguage" to targetLanguage,
                "isOffline" to isOffline.toString(),
                "isSuccessful" to isSuccessful.toString(),
                "translatedText" to (translatedText?.take(100) ?: ""),
                "errorMessage" to (errorMessage ?: "")
            )
            
            // Send the data to the Google Sheet
            val result = sendPostRequest(SCRIPT_URL, postData)
            Log.d(TAG, "Log result: $result")
            
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error logging translation attempt", e)
            return@withContext "Error: ${e.message}"
        }
    }
    
    /**
     * Log a JavaScript event
     */
    suspend fun logJavaScriptEvent(
        eventType: String,
        elementId: String,
        elementText: String,
        additionalInfo: String?
    ) = withContext(Dispatchers.IO) {
        try {
            // Format the current timestamp
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            // Prepare the data to send
            val postData = mapOf(
                "action" to "logJavaScriptEvent",
                "sheetId" to SHEET_ID,
                "timestamp" to timestamp,
                "sessionId" to sessionId,
                "deviceInfo" to deviceInfo,
                "eventType" to eventType,
                "elementId" to elementId,
                "elementText" to elementText.take(100), // Limit text length
                "additionalInfo" to (additionalInfo ?: "")
            )
            
            // Send the data to the Google Sheet
            val result = sendPostRequest(SCRIPT_URL, postData)
            Log.d(TAG, "Log result: $result")
            
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error logging JavaScript event", e)
            return@withContext "Error: ${e.message}"
        }
    }
    
    /**
     * Send a POST request to the specified URL with the given data
     */
    private fun sendPostRequest(urlString: String, data: Map<String, String>): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            // Set up the connection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            
            // Build the POST data
            val postData = StringBuilder()
            for ((key, value) in data) {
                if (postData.isNotEmpty()) {
                    postData.append('&')
                }
                postData.append(URLEncoder.encode(key, "UTF-8"))
                postData.append('=')
                postData.append(URLEncoder.encode(value, "UTF-8"))
            }
            
            // Send the data
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(postData.toString())
            writer.flush()
            writer.close()
            
            // Get the response
            val responseCode = connection.responseCode
            return if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                "Error: HTTP $responseCode"
            }
        } finally {
            connection.disconnect()
        }
    }
}
