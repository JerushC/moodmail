package com.jerush.moodmail

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.jerush.moodmail.gmail.AuthResult
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/** Throwaway debug screen. Real UI comes later; this only proves the pipeline end to end. */
class MainActivity : ComponentActivity() {
    private lateinit var log: TextView
    private lateinit var draft: EditText
    private var latest: Pair<Email, MoodReading>? = null
    private var draftJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        log = TextView(this).apply { setTextIsSelectable(true) }
        draft = EditText(this).apply { hint = "Draft a reply to the top email" }
        val run = Button(this).apply { text = "Sign in + analyze inbox" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(run); addView(draft); addView(log)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        run.setOnClickListener { lifecycleScope.launch { analyzeInbox() } }
        draft.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { onDraftChanged(s?.toString().orEmpty()) }
        })
    }

    private suspend fun analyzeInbox() {
        say("Loading model…")
        ServiceLocator.moodAnalyzer.warmUp { p -> runOnUiThread { log.text = "Downloading model ${(p * 100).toInt()}%" } }
        say("Model ready. Signing in to Gmail…")
        val token = when (val r = ServiceLocator.gmail.authorize(this)) {
            is AuthResult.Authorized -> r.accessToken
            is AuthResult.Failed -> { say("Auth failed: ${r.reason}"); return }
        }
        val emails = ServiceLocator.gmail.fetchRecent(token, max = 5)
        say("Fetched ${emails.size} emails")
        for (e in emails) {
            val reading = ServiceLocator.moodAnalyzer.analyzeEmail(e)
            if (latest == null) latest = e to reading
            say("• ${e.subject}\n  from ${e.from} at ${e.sentAt}\n  $reading")
            if (ServiceLocator.policy.shouldNotify(e, reading)) ServiceLocator.notifier.heatedEmail(e, reading)
        }
    }

    private fun onDraftChanged(text: String) {
        draftJob?.cancel()
        if (text.isBlank()) return
        draftJob = lifecycleScope.launch {
            delay(1200) // re-check once typing pauses
            val (email, incoming) = latest ?: (null to null)
            val reading = ServiceLocator.moodAnalyzer.analyzeDraft(text, email)
            val warning = ServiceLocator.policy.forReply(ZonedDateTime.now(), reading, email, incoming)
            say("Draft mood: $reading")
            if (warning != null) {
                say("⚠ ${warning.title}: ${warning.message}")
                ServiceLocator.notifier.calmDown(warning)
            }
        }
    }

    private fun say(line: String) = runOnUiThread { log.append("\n$line") }
}
