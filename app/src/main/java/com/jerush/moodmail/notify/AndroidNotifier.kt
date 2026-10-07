package com.jerush.moodmail.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jerush.moodmail.MainActivity
import com.jerush.moodmail.model.CalmDownWarning
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading

class AndroidNotifier(private val context: Context) : Notifier {

    override fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEATED, "Heated mail", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Emails that read as angry or tense"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALM, "Calm down", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders to cool off before replying"
            },
        )
    }

    override fun heatedEmail(email: Email, reading: MoodReading) {
        val emotion = reading.emotion.name.lowercase().replace('_', ' ')
        val tone = reading.tone.name.lowercase().replace('_', ' ')
        val notification = NotificationCompat.Builder(context, CHANNEL_HEATED)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Heated email from ${email.from}")
            .setContentText("$emotion, $tone, ${reading.intensity}/10: ${email.subject}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$emotion, $tone, ${reading.intensity}/10: ${email.subject}"),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(email.id.hashCode()))
            .setAutoCancel(true)
            .build()
        post(email.id.hashCode(), notification)
    }

    override fun calmDown(warning: CalmDownWarning) {
        val notification = NotificationCompat.Builder(context, CHANNEL_CALM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(warning.title)
            .setContentText(warning.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(warning.message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(CALM_DOWN_ID))
            .setAutoCancel(true)
            .build()
        // One fixed id so a new warning replaces the previous one instead of stacking.
        post(CALM_DOWN_ID, notification)
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun post(id: Int, notification: android.app.Notification) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call. Nothing to do.
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_HEATED = "heated_mail"
        const val CHANNEL_CALM = "calm_down"
        private const val CALM_DOWN_ID = 1
    }
}
