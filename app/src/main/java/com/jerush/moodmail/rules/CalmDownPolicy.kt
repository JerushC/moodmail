package com.jerush.moodmail.rules

import com.jerush.moodmail.model.CalmDownWarning
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading
import java.time.ZonedDateTime

interface CalmDownPolicy {
    /** Warning to show while the user drafts a reply, or null if none is warranted. */
    fun forReply(now: ZonedDateTime, draft: MoodReading?, replyingTo: Email?, incoming: MoodReading?): CalmDownWarning?

    /** Whether an incoming email should raise a notification. */
    fun shouldNotify(email: Email, reading: MoodReading): Boolean
}
