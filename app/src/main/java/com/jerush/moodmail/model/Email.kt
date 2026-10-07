package com.jerush.moodmail.model

import java.time.Instant

data class Email(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val snippet: String,
    /** Plain-text body, best effort. Falls back to [snippet] when no text part exists. */
    val body: String,
    val sentAt: Instant,
)
