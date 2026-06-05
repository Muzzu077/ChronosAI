package com.example.android_integration

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * SupabaseManager: Thread-safe Kotlin Singleton for Supabase Authentication and Database opérations.
 * Underneath, it uses the official Supabase Kotlin Client (`io.github.jan-tennert.supabase`).
 *
 * DROP-IN INSTRUCTIONS:
 * 1. Place this file inside your Android codebase (e.g., `app/src/main/java/[your_package]/integration`).
 * 2. Ensure your `build.gradle.kts` has the supabase dependencies:
 *    - `implementation("io.github.jan-tennert.supabase:gotrue-kt:[version]")`
 *    - `implementation("io.github.jan-tennert.supabase:postgrest-kt:[version]")`
 */
object SupabaseManager {
    private const val TAG = "SupabaseManager"

    // Supabase URL & Public Anon Key injected from Mission Control State
    private const val SUPABASE_URL = "https://bobdgiankoywtyysxtnl.supabase.co"
    private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJvYmRnaWFua295d3R5eXN4dG5sIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc4MDU2MzYwOSwiZXhwIjoyMDk2MTM5NjA5fQ.jTROmnCEyxn4GZalhjFqjUu_7q2k7aMIscZXTILxHj0"

    // Thread-safe Lazy Initialization of SupabaseClient
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
    }

    /**
     * Typed Task class representation corresponding to SQLite / Supabase PostgreSQL table format.
     */
    @Serializable
    data class DailyTask(
        val id: String? = null,
        val user_id: String,
        val task_description: String,
        val scheduled_time: String,
        val status: String,
        val created_at: String? = null
    )

    /**
     * Signs in user using Email and Password credential provider.
     * Access token is cached in current gotrue state upon success.
     *
     * @return User ID (UUID) string on authentication success.
     */
    suspend fun signInWithEmail(email: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating authentication sequence for user: $email")
            
            // Call official Supabase GoTrue authentication provider
            val session = client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            
            val userId = client.auth.currentUserOrNull()?.id
            Log.d(TAG, "Sign In successful. Authorized User ID: $userId")
            return@withContext userId
        } catch (e: Exception) {
            Log.e(TAG, "Authentication failure: ${e.message}", e)
            throw e
        }
    }

    /**
     * Retrieves all scheduled tasks for the currently authenticated user session.
     */
    suspend fun fetchDailyTasks(): List<DailyTask> = withContext(Dispatchers.IO) {
        try {
            val userId = client.auth.currentUserOrNull()?.id ?: "00000000-0000-0000-0000-000000000000"
            
            Log.d(TAG, "Querying daily_tasks table for user_id: $userId")
            
            // Retrieve data matching user_id bound by RLS selective boundaries
            val results = client.postgrest["daily_tasks"]
                .select {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<DailyTask>()
                
            Log.d(TAG, "Query resolved. Fetched ${results.size} tasks safely.")
            return@withContext results
        } catch (e: Exception) {
            Log.e(TAG, "Query execution error retrieving tasks: ${e.message}", e)
            throw e
        }
    }

    suspend fun insertTask(taskDescription: String, scheduledTimeIso: String): DailyTask? = withContext(Dispatchers.IO) {
        try {
            val userId = client.auth.currentUserOrNull()?.id ?: "00000000-0000-0000-0000-000000000000"
            val newTask = DailyTask(
                user_id = userId,
                task_description = taskDescription,
                scheduled_time = scheduledTimeIso,
                status = "pending"
            )
            val result = client.postgrest["daily_tasks"]
                .insert(newTask) { select() }
                .decodeSingleOrNull<DailyTask>()
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert task: ${e.message}", e)
            throw e
        }
    }

    /**
     * Updates the status of an existing task.
     */
    suspend fun updateTaskStatus(taskId: String, newStatus: String) = withContext(Dispatchers.IO) {
        try {
            val userId = client.auth.currentUserOrNull()?.id ?: "00000000-0000-0000-0000-000000000000"
            client.postgrest["daily_tasks"]
                .update({ "status" to newStatus }) {
                    filter {
                        eq("id", taskId)
                        eq("user_id", userId)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update task status: ${e.message}", e)
            throw e
        }
    }

    /**
     * Deletes a task from the backend.
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        try {
            val userId = client.auth.currentUserOrNull()?.id ?: "00000000-0000-0000-0000-000000000000"
            client.postgrest["daily_tasks"]
                .delete {
                    filter {
                        eq("id", taskId)
                        eq("user_id", userId)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete task: ${e.message}", e)
            throw e
        }
    }

    /**
     * Helper check to verify if the user is currently verified in gotrue session cache.
     */
    fun getActiveUserId(): String? {
        return client.auth.currentUserOrNull()?.id
    }
}
