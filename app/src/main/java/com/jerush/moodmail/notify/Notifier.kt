package com.jerush.moodmail.notify

import com.jerush.moodmail.model.CalmDownWarning
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading

interface Notifier {
    /** Creates notification channels. Call once from Application.onCreate. */
    fun ensureChannels()
    fun heatedEmail(email: Email, reading: MoodReading)
    fun calmDown(warning: CalmDownWarning)
}
