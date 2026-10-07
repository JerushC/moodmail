package com.jerush.moodmail.model

enum class Emotion { ANGER, FRUSTRATION, ANXIETY, SADNESS, NEUTRAL, CALM, JOY, GRATITUDE }

enum class Tone { HOSTILE, PASSIVE_AGGRESSIVE, CURT, URGENT, FORMAL, NEUTRAL, FRIENDLY, WARM }

data class MoodReading(
    val emotion: Emotion,
    val tone: Tone,
    /** 0 (flat) to 10 (extreme). */
    val intensity: Int,
    /** True when the text reads as heated enough that replying now is risky. */
    val heated: Boolean,
    /** One short sentence quoting or pointing at the words that drove the reading. */
    val rationale: String,
)
