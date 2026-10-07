package com.jerush.moodmail.rules

import com.jerush.moodmail.model.CalmDownWarning
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading
import com.jerush.moodmail.model.WarningLevel
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime

/**
 * Warns harder after hours (from [cutoff] until [morning]) and whenever the text reads heated.
 */
class EveningCalmDownPolicy(
    private val cutoff: LocalTime = LocalTime.of(17, 0),
    private val morning: LocalTime = LocalTime.of(6, 0),
    private val zone: ZoneId = ZoneId.systemDefault(),
) : CalmDownPolicy {

    override fun forReply(
        now: ZonedDateTime,
        draft: MoodReading?,
        replyingTo: Email?,
        incoming: MoodReading?,
    ): CalmDownWarning? {
        val time = now.toLocalTime()
        val draftHot = draft != null && (draft.heated || draft.intensity >= DRAFT_STRONG_INTENSITY)
        val incomingHot = incoming?.heated == true

        if (isAfterHours(time)) {
            if (draftHot || incomingHot) {
                val (who, reading) = if (draftHot) "Your draft" to draft!! else "Their email" to incoming!!
                return CalmDownWarning(
                    level = WarningLevel.STRONG,
                    title = "Step away first",
                    message = "It's ${formatHour(time)} and ${who.lowercase()} reads as " +
                        "${reading.emotion.label()}. Take five minutes, then look at it again.",
                )
            }
            return CalmDownWarning(
                level = WarningLevel.NUDGE,
                title = "It's after 5",
                message = "Save this as a draft and send it in the morning. It will read better with fresh eyes.",
            )
        }

        if (draft?.heated == true) {
            return CalmDownWarning(
                level = WarningLevel.NUDGE,
                title = "Sounds a bit heated",
                message = "This reads as ${draft.emotion.label()}. Maybe give it a minute before you hit send.",
            )
        }
        return null
    }

    override fun shouldNotify(email: Email, reading: MoodReading): Boolean {
        if (reading.heated || reading.intensity >= NOTIFY_INTENSITY) return true
        val sentLocal = email.sentAt.atZone(zone).toLocalTime()
        return isAfterHours(sentLocal) && reading.intensity >= NOTIFY_AFTER_HOURS_INTENSITY
    }

    private fun isAfterHours(time: LocalTime): Boolean = time >= cutoff || time < morning

    private fun formatHour(time: LocalTime): String {
        val h12 = if (time.hour % 12 == 0) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "am" else "pm"
        return "%d:%02d %s".format(h12, time.minute, suffix)
    }

    private fun Enum<*>.label(): String = name.lowercase().replace('_', ' ')

    private companion object {
        const val DRAFT_STRONG_INTENSITY = 6
        const val NOTIFY_INTENSITY = 7
        const val NOTIFY_AFTER_HOURS_INTENSITY = 5
    }
}
