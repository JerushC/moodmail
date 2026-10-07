package com.jerush.moodmail

import android.content.Context
import com.jerush.moodmail.gmail.GmailRepository
import com.jerush.moodmail.gmail.GoogleGmailRepository
import com.jerush.moodmail.llm.MelangeMoodAnalyzer
import com.jerush.moodmail.llm.MoodAnalyzer
import com.jerush.moodmail.notify.AndroidNotifier
import com.jerush.moodmail.notify.Notifier
import com.jerush.moodmail.rules.CalmDownPolicy
import com.jerush.moodmail.rules.EveningCalmDownPolicy
import com.jerush.moodmail.vision.FaceMoodProbe
import com.jerush.moodmail.vision.MelangeFaceMoodProbe

/** Single wiring point. Each lane provides the concrete class named here. */
object ServiceLocator {
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    val moodAnalyzer: MoodAnalyzer by lazy { MelangeMoodAnalyzer(app, BuildConfig.MELANGE_PERSONAL_KEY) }
    val gmail: GmailRepository by lazy { GoogleGmailRepository(app) }
    val policy: CalmDownPolicy by lazy { EveningCalmDownPolicy() }
    val notifier: Notifier by lazy { AndroidNotifier(app) }
    val faceProbe: FaceMoodProbe by lazy { MelangeFaceMoodProbe(app, BuildConfig.MELANGE_PERSONAL_KEY) }
}
