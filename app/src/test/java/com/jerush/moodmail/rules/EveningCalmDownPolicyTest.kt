package com.jerush.moodmail.rules

import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.Emotion
import com.jerush.moodmail.model.MoodReading
import com.jerush.moodmail.model.Tone
import com.jerush.moodmail.model.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EveningCalmDownPolicyTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val policy = EveningCalmDownPolicy(zone = zone)

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, 6, hour, minute, 0, 0, zone)

    private fun reading(
        heated: Boolean = false,
        intensity: Int = 2,
        emotion: Emotion = if (heated) Emotion.ANGER else Emotion.CALM,
    ) = MoodReading(emotion, if (heated) Tone.HOSTILE else Tone.NEUTRAL, intensity, heated, "r")

    private val heated = reading(heated = true, intensity = 8)
    private val calm = reading()

    private fun email(hour: Int, minute: Int = 0) = Email(
        id = "id", threadId = "t", from = "a@b.c", subject = "s", snippet = "", body = "",
        sentAt = at(hour, minute).toInstant(),
    )

    // forReply: boundaries

    @Test fun reply_1659_calmDraft_noWarning() = assertNull(policy.forReply(at(16, 59), calm, null, calm))

    @Test fun reply_1659_heatedDraft_nudge() {
        val w = policy.forReply(at(16, 59), heated, null, calm)
        assertEquals(WarningLevel.NUDGE, w?.level)
    }

    @Test fun reply_1700_calm_nudgeAboutAfterFive() {
        val w = policy.forReply(at(17, 0), calm, null, calm)!!
        assertEquals(WarningLevel.NUDGE, w.level)
        assertEquals("It's after 5", w.title)
        assertTrue(w.message.contains("morning"))
    }

    @Test fun reply_1700_heatedDraft_strong() {
        assertEquals(WarningLevel.STRONG, policy.forReply(at(17, 0), heated, null, calm)?.level)
    }

    @Test fun reply_2330_heatedDraft_strongNamesHourAndEmotion() {
        val w = policy.forReply(at(23, 30), heated, null, calm)!!
        assertEquals(WarningLevel.STRONG, w.level)
        assertEquals("Step away first", w.title)
        assertTrue(w.message, w.message.contains("11:30 pm"))
        assertTrue(w.message, w.message.contains("anger"))
    }

    @Test fun reply_0559_heatedIncoming_strong() {
        val w = policy.forReply(at(5, 59), calm, null, heated)!!
        assertEquals(WarningLevel.STRONG, w.level)
        assertTrue(w.message, w.message.contains("5:59 am"))
        assertTrue(w.message, w.message.contains("anger"))
    }

    @Test fun reply_0600_calm_noWarning() = assertNull(policy.forReply(at(6, 0), calm, null, calm))

    @Test fun reply_0600_heatedDraft_nudgeOnly() {
        assertEquals(WarningLevel.NUDGE, policy.forReply(at(6, 0), heated, null, null)?.level)
    }

    @Test fun reply_midnightIsTwelveAm() {
        val w = policy.forReply(at(0, 5), heated, null, null)!!
        assertTrue(w.message, w.message.contains("12:05 am"))
    }

    // forReply: intensity trigger and missing readings

    @Test fun reply_afterHours_draftIntensity6_notHeated_strong() {
        val draft = reading(heated = false, intensity = 6, emotion = Emotion.FRUSTRATION)
        val w = policy.forReply(at(20, 0), draft, null, calm)!!
        assertEquals(WarningLevel.STRONG, w.level)
        assertTrue(w.message.contains("frustration"))
    }

    @Test fun reply_afterHours_draftIntensity5_nudge() {
        val draft = reading(heated = false, intensity = 5)
        assertEquals(WarningLevel.NUDGE, policy.forReply(at(20, 0), draft, null, calm)?.level)
    }

    @Test fun reply_daytime_highIntensityButNotHeated_noWarning() {
        assertNull(policy.forReply(at(10, 0), reading(intensity = 9), null, null))
    }

    @Test fun reply_afterHours_noReadings_nudge() {
        assertEquals(WarningLevel.NUDGE, policy.forReply(at(18, 0), null, null, null)?.level)
    }

    @Test fun reply_daytime_nothing_null() = assertNull(policy.forReply(at(12, 0), null, null, null))

    // shouldNotify

    @Test fun notify_heatedAnyTime() = assertTrue(policy.shouldNotify(email(10), heated.copy(intensity = 1)))

    @Test fun notify_intensity7_notHeated_daytime() =
        assertTrue(policy.shouldNotify(email(10), reading(intensity = 7)))

    @Test fun notify_intensity6_daytime_false() =
        assertFalse(policy.shouldNotify(email(10), reading(intensity = 6)))

    @Test fun notify_sentAt1800_intensity5_true() =
        assertTrue(policy.shouldNotify(email(18), reading(intensity = 5)))

    @Test fun notify_sentAt1800_intensity4_false() =
        assertFalse(policy.shouldNotify(email(18), reading(intensity = 4)))

    @Test fun notify_sentAt1659_intensity5_false() =
        assertFalse(policy.shouldNotify(email(16, 59), reading(intensity = 5)))

    @Test fun notify_sentAt1700_intensity5_true() =
        assertTrue(policy.shouldNotify(email(17, 0), reading(intensity = 5)))

    @Test fun notify_sentAt0559_intensity5_true() =
        assertTrue(policy.shouldNotify(email(5, 59), reading(intensity = 5)))

    @Test fun notify_sentAt0600_intensity5_false() =
        assertFalse(policy.shouldNotify(email(6, 0), reading(intensity = 5)))

    @Test fun notify_calmAfterHours_lowIntensity_false() =
        assertFalse(policy.shouldNotify(email(23, 30), calm))

    @Test fun notify_usesInjectedZone() {
        // 18:00 UTC is 11:00 in Los Angeles (UTC-7 in October), so not after hours there.
        val la = EveningCalmDownPolicy(zone = ZoneId.of("America/Los_Angeles"))
        assertFalse(la.shouldNotify(email(18), reading(intensity = 5)))
        assertTrue(la.shouldNotify(email(1), reading(intensity = 5))) // 01:00 UTC = 18:00 PDT previous day
    }

    @Test fun reply_customCutoffRespected() {
        val early = EveningCalmDownPolicy(cutoff = java.time.LocalTime.of(15, 0), zone = zone)
        assertNotNull(early.forReply(at(15, 0), calm, null, null))
    }
}
