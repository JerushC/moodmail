package com.jerush.moodmail.llm

import android.content.Context
import android.util.Log
import com.jerush.moodmail.model.Email
import com.jerush.moodmail.model.MoodReading
import com.zeticai.mlange.core.model.llm.LLMInitOption
import com.zeticai.mlange.core.model.llm.ZeticMLangeLLMModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * [MoodAnalyzer] backed by Liquid AI LFM2.5-1.2B-Instruct running on-device through Zetic Melange 1.11.0.
 *
 * The native model is single-session and not thread-safe, so every call (load, generate, close) goes
 * through one [Mutex] on [Dispatchers.IO]. Analysis never throws for model problems: a failed load or a
 * garbled answer falls back to [MoodParser]'s keyword heuristic over the source text. Coroutine
 * cancellation is still propagated.
 */
class MelangeMoodAnalyzer(
    context: Context,
    private val personalKey: String,
) : MoodAnalyzer {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var model: ZeticMLangeLLMModel? = null

    @Volatile
    private var closed = false

    override suspend fun warmUp(onProgress: (Float) -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.IO) { ensureModel(onProgress) }
        }
    }

    override suspend fun analyzeEmail(email: Email): MoodReading {
        val source = MoodPrompts.prepareBody(
            "${email.subject}\n${email.body.ifBlank { email.snippet }}",
            MoodPrompts.MAX_BODY_CHARS,
        )
        return analyze(MoodPrompts.buildEmailPrompt(email), source)
    }

    override suspend fun analyzeDraft(draft: String, replyingTo: Email?): MoodReading {
        val source = MoodPrompts.prepareBody(draft, MoodPrompts.MAX_BODY_CHARS)
        return analyze(MoodPrompts.buildDraftPrompt(draft, replyingTo), source)
    }

    override fun close() {
        closed = true
        // Take the lock if it is free so we never free native memory mid-generation. If a generation is
        // running, it sees `closed` after its loop and releases the model itself.
        if (mutex.tryLock()) {
            try {
                releaseModel()
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun analyze(prompt: String, sourceText: String): MoodReading =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val raw = try {
                    generate(prompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // ZeticMLangeException from run(), native errors, or a failed download.
                    Log.w(TAG, "Melange generation failed, using heuristic: ${e.javaClass.simpleName}: ${e.message}")
                    null
                } finally {
                    if (closed) releaseModel()
                }
                if (raw.isNullOrBlank()) MoodParser.heuristic(sourceText) else MoodParser.parse(raw, sourceText)
            }
        }

    /** Must be called with [mutex] held, on [Dispatchers.IO]. Idempotent. */
    private fun ensureModel(onProgress: (Float) -> Unit = {}): ZeticMLangeLLMModel {
        check(!closed) { "MelangeMoodAnalyzer is closed" }
        model?.let {
            onProgress(1f)
            return it
        }
        val loaded = ZeticMLangeLLMModel(
            context = appContext,
            personalKey = personalKey,
            name = MODEL_NAME,
            initOption = LLMInitOption(nCtx = CONTEXT_TOKENS),
            onDownload = { progress -> onProgress(progress.coerceIn(0f, 1f)) },
        )
        model = loaded
        onProgress(1f)
        return loaded
    }

    /**
     * Runs one prompt and returns the raw text. Stops at [MAX_OUTPUT_TOKENS], at the end-of-generation
     * signal (same condition the SDK's own generate() uses), or as soon as the first JSON object closes.
     * Must be called with [mutex] held, on [Dispatchers.IO].
     */
    private suspend fun generate(prompt: String): String {
        val llm = ensureModel()
        // Each analysis is independent: drop KV state from the previous call before starting.
        llm.cleanUp()
        val out = StringBuilder()
        try {
            llm.run(prompt)
            var depth = 0
            var seenOpen = false
            var inString = false
            var escaped = false
            var tokens = 0
            loop@ while (tokens < MAX_OUTPUT_TOKENS) {
                coroutineContext.ensureActive()
                val next = llm.waitForNextToken()
                if (next.status != 0 || next.token.isEmpty()) break
                tokens++
                out.append(next.token)
                for (c in next.token) {
                    if (inString) {
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> inString = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> if (seenOpen) inString = true
                        '{' -> { seenOpen = true; depth++ }
                        '}' -> if (seenOpen && --depth == 0) break@loop
                    }
                }
                if (next.isFinal) break
            }
        } finally {
            // Stopping early leaves the native session mid-generation; reset it so the next run starts clean.
            runCatching { llm.cleanUp() }
        }
        return out.toString()
    }

    private fun releaseModel() {
        val m = model ?: return
        model = null
        runCatching { m.close() }
            .onFailure { Log.w(TAG, "Melange close failed: ${it.message}") }
    }

    companion object {
        const val MODEL_NAME = "zetic/LFM2.5-1.2B-Instruct"

        /** Output cap. A full answer is roughly 40 to 80 tokens; the brace check usually stops it first. */
        const val MAX_OUTPUT_TOKENS = 200

        /**
         * Context window. The system prompt with examples is roughly 1k tokens and the bodies are capped
         * at 1500 + 600 chars, so the SDK default of 2048 is too tight for draft mode.
         */
        const val CONTEXT_TOKENS = 4096

        private const val TAG = "MelangeMoodAnalyzer"
    }
}
