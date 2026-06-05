package com.example.android_integration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ApiClient: Handles synchronization and communication with the FastAPI API Gateway.
 * It connects to the host machine via the Android emulator's loopback address (http://10.0.2.2:8000).
 *
 * DROP-IN INSTRUCTIONS:
 * 1. Drop this file into your package integration folder.
 * 2. Invoke `fetchLiveKitToken` inside any CoroutineScope or ViewModel when joining the AI voice portal.
 */
class ApiClient {

    companion object {
        private const val TAG = "ApiClient"
        
        // Using localhost:8080 with adb reverse for physical device testing
        private const val BASE_URL = "http://localhost:8080"
    }

    /**
     * Queries the FastAPI backend gateway to obtain a signed JWT listener token for LiveKit WebRTC.
     *
     * @param userId The unique user ID of the device caller.
     * @return Signed LiveKit connection token (JWT).
     */
    suspend fun fetchLiveKitToken(userId: String): String = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/get-listen-token?user_id=$userId"
        Log.d(TAG, "Fetching secure JWT listen token from Gateway: $endpoint")
        
        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val token = jsonResponse.getString("token")
                
                Log.d(TAG, "Bearer JWT recovered successfully. Token length: ${token.length}")
                return@withContext token
            } else {
                val errorStream = connection.errorStream
                val errorMessage = errorStream?.let {
                    BufferedReader(InputStreamReader(it)).readText()
                } ?: "Unknown Gateway connection error"
                
                Log.e(TAG, "Gateway returned bad status code: $responseCode - $errorMessage")
                throw Exception("API Gateway Error (Status $responseCode): $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Networking connectivity exception of API Gateway: ${e.message}", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }
}
