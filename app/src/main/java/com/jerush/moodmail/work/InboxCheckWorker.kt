package com.jerush.moodmail.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jerush.moodmail.ServiceLocator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class InboxCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val token = ServiceLocator.gmail.silentToken() ?: return Result.success() // user must open the app
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Ordered newest-first so the cap evicts the oldest IDs, not arbitrary ones.
            val seen = LinkedHashSet<String>(
                prefs.getString(KEY_SEEN_IDS, "").orEmpty().split('\n').filter { it.isNotEmpty() },
            )

            val fresh = ServiceLocator.gmail.fetchRecent(token, FETCH_COUNT).filter { it.id !in seen }
            if (fresh.isNotEmpty()) {
                ServiceLocator.moodAnalyzer.warmUp()
                for (email in fresh) {
                    val reading = ServiceLocator.moodAnalyzer.analyzeEmail(email)
                    if (ServiceLocator.policy.shouldNotify(email, reading)) {
                        ServiceLocator.notifier.heatedEmail(email, reading)
                    }
                    // Record per email so a retry after a mid-batch failure does not re-notify.
                    seen.remove(email.id)
                    val updated = LinkedHashSet<String>().apply { add(email.id); addAll(seen) }
                    seen.clear()
                    seen.addAll(updated.take(MAX_SEEN))
                    prefs.edit().putString(KEY_SEEN_IDS, seen.joinToString("\n")).apply()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "inbox-check"
        private const val PREFS = "moodmail_inbox"
        private const val KEY_SEEN_IDS = "seen_ids"
        private const val MAX_SEEN = 200
        private const val FETCH_COUNT = 10
        private const val MAX_ATTEMPTS = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InboxCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
