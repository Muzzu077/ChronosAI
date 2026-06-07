package com.example.android_integration

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class DailyTaskDto(
    val id: String,
    val userId: String,
    val taskDescription: String,
    val scheduledTime: String,
    val status: String
)

data class LiveKitSessionDto(
    val token: String,
    val roomName: String,
    val identity: String,
    val serverUrl: String
)

class ApiClient {

    companion object {
        private const val TAG = "ApiClient"

        // Redirected via adb reverse for physical USB devices, or uses 10.0.2.2 for emulator.
        private val BASE_URL: String
            get() {
                val isEmulator = android.os.Build.FINGERPRINT.startsWith("generic")
                        || android.os.Build.FINGERPRINT.startsWith("unknown")
                        || android.os.Build.MODEL.contains("google_sdk")
                        || android.os.Build.MODEL.contains("Emulator")
                        || android.os.Build.MODEL.contains("Android SDK built for x86")
                        || android.os.Build.MANUFACTURER.contains("Genymotion")
                        || (android.os.Build.BRAND.startsWith("generic") && android.os.Build.DEVICE.startsWith("generic"))
                        || "google_sdk" == android.os.Build.PRODUCT
                return if (isEmulator) "http://10.0.2.2:8080" else "http://127.0.0.1:8080"
            }
    }

    suspend fun fetchLiveKitSession(userId: String): LiveKitSessionDto = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/get-listen-token?user_id=${encode(userId)}"
        val jsonResponse = requestJson("GET", endpoint)
        LiveKitSessionDto(
            token = jsonResponse.getString("token"),
            roomName = jsonResponse.getString("room_name"),
            identity = jsonResponse.getString("identity"),
            serverUrl = jsonResponse.getString("server_url")
        )
    }

    suspend fun fetchLiveKitToken(userId: String): String = fetchLiveKitSession(userId).token

    suspend fun fetchDailyTasks(userId: String): List<DailyTaskDto> = withContext(Dispatchers.IO) {
        val endpoint = "$BASE_URL/tasks?user_id=${encode(userId)}"
        val jsonResponse = requestJson("GET", endpoint)
        val tasks = jsonResponse.optJSONArray("tasks") ?: JSONArray()
        List(tasks.length()) { index -> tasks.getJSONObject(index).toDailyTaskDto() }
    }

    suspend fun createTask(userId: String, taskDescription: String, scheduledTimeIso: String): DailyTaskDto =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("user_id", userId)
                .put("task_description", taskDescription)
                .put("scheduled_time", scheduledTimeIso)
            requestJson("POST", "$BASE_URL/tasks", body).getJSONObject("task").toDailyTaskDto()
        }

    suspend fun updateTaskStatus(userId: String, taskId: String, newStatus: String): DailyTaskDto =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("user_id", userId)
                .put("status", newStatus)
            requestJson("PATCH", "$BASE_URL/tasks/$taskId/status", body).getJSONObject("task").toDailyTaskDto()
        }

    suspend fun deleteTask(userId: String, taskId: String) = withContext(Dispatchers.IO) {
        request("DELETE", "$BASE_URL/tasks/$taskId?user_id=${encode(userId)}")
    }

    suspend fun updateProfile(userId: String, displayName: String, role: String, primaryGoal: String, timezone: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("display_name", displayName)
                .put("role", role)
                .put("primary_goal", primaryGoal)
                .put("timezone", timezone)
            requestJson("PUT", "$BASE_URL/users/$userId/profile", body)
        }

    private fun requestJson(method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val responseText = request(method, endpoint, body)
        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }

    private fun request(method: String, endpoint: String, body: JSONObject? = null): String {
        Log.d(TAG, "$method $endpoint")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
            }

            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Content-Length", bytes.size.toString())
                connection.outputStream.use { it.write(bytes) }
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                return connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            val errorMessage = connection.errorStream?.let {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: "Unknown API gateway error"
            Log.e(TAG, "Gateway returned $responseCode: $errorMessage")
            throw IllegalStateException("API Gateway Error ($responseCode): $errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Gateway request failed: ${e.message}", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun JSONObject.toDailyTaskDto(): DailyTaskDto {
        return DailyTaskDto(
            id = getString("id"),
            userId = getString("user_id"),
            taskDescription = getString("task_description"),
            scheduledTime = getString("scheduled_time"),
            status = getString("status")
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
