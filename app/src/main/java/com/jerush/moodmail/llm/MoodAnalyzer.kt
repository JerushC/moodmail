package com.jerush.moodmail.llm

import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading

/** Reads emotion and tone from text with an on-device Liquid AI LFM model via Melange. */
interface MoodAnalyzer {
    /** Downloads (first run) and initializes the model. Safe to call more than once. */
    suspend fun warmUp(onProgress: (Float) -> Unit = {})

    /** Mood of an email someone sent to the user. */
    suspend fun analyzeEmail(email: Email): MoodReading

    /** Mood of the user's own in-progress reply draft. */
    suspend fun analyzeDraft(draft: String, replyingTo: Email?): MoodReading

    fun close()
}
