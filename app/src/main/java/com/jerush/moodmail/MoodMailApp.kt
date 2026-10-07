package com.jerush.moodmail

import android.app.Application
import com.jerush.moodmail.work.InboxCheckWorker

class MoodMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.notifier.ensureChannels()
        InboxCheckWorker.schedule(this)
    }
}
