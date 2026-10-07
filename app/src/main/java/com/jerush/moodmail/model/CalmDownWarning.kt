package com.jerush.moodmail.model

enum class WarningLevel { NUDGE, STRONG }

data class CalmDownWarning(
    val level: WarningLevel,
    val title: String,
    val message: String,
)
