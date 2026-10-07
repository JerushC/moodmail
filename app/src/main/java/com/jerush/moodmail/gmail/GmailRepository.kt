package com.jerush.moodmail.gmail

import androidx.activity.ComponentActivity
import com.jerush.moodmail.model.Email

sealed interface AuthResult {
    data class Authorized(val accessToken: String) : AuthResult
    data class Failed(val reason: String) : AuthResult
}

interface GmailRepository {
    /** Interactive OAuth from a foreground activity. Requests gmail.readonly only. */
    suspend fun authorize(activity: ComponentActivity): AuthResult

    /** Non-interactive token refresh for background work. Null when the user must sign in again. */
    suspend fun silentToken(): String?

    /** Most recent inbox messages, newest first. */
    suspend fun fetchRecent(accessToken: String, max: Int = 10): List<Email>
}
