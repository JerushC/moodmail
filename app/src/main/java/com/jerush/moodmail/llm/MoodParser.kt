package com.jerush.moodmail.llm

import com.jerush.moodmail.model.Emotion
import com.jerush.moodmail.model.MoodReading
import com.jerush.moodmail.model.Tone
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Turns raw model output into a [MoodReading]. Never throws.
 *
 * Order of attempts:
 *  1. First balanced `{...}` object in the output, parsed with org.json (code fences and prose
 *     around it are ignored; a truncated object is closed and retried).
 *  2. Regex field extraction, for malformed JSON the model half-got right.
 *  3. A keyword heuristic over [sourceText] (or the raw output when no source is given).
 *
 * `heated` is always recomputed with [isHeated]; the model's own flag is ignored when it disagrees.
 */
object MoodParser {

    private val NEGATIVE_EMOTIONS = setOf(Emotion.ANGER, Emotion.FRUSTRATION, Emotion.ANXIETY, Emotion.SADNESS)
    private const val MAX_RATIONALE_CHARS = 240

    /** Heated rule shared with the system prompt: strong negative feeling, or a hostile / passive-aggressive tone. */
    fun isHeated(emotion: Emotion, tone: Tone, intensity: Int): Boolean =
        (intensity >= 6 && emotion in NEGATIVE_EMOTIONS) ||
            tone == Tone.HOSTILE || tone == Tone.PASSIVE_AGGRESSIVE

    fun parse(raw: String): MoodReading = parse(raw, null)

    /**
     * @param sourceText the email or draft that was analyzed. Used only by the heuristic fallback,
     * so a failed model output still gets judged on the real words instead of defaulting to NEUTRAL.
     */
    fun parse(raw: String, sourceText: String?): MoodReading {
        val fields = extractFromJson(raw) ?: extractWithRegex(raw)
        if (fields == null || (fields.emotion == null && fields.tone == null)) {
            return heuristic(sourceText ?: raw)
        }
        val emotion = fields.emotion ?: Emotion.NEUTRAL
        val tone = fields.tone ?: Tone.NEUTRAL
        val intensity = (fields.intensity ?: defaultIntensity(emotion, tone)).coerceIn(0, 10)
        return MoodReading(
            emotion = emotion,
            tone = tone,
            intensity = intensity,
            heated = isHeated(emotion, tone, intensity),
            rationale = fields.rationale.orEmpty().trim().take(MAX_RATIONALE_CHARS),
        )
    }

    // ---------------------------------------------------------------- label mapping

    fun mapEmotion(label: String?): Emotion? {
        val key = normalize(label) ?: return null
        Emotion.entries.firstOrNull { it.name == key }?.let { return it }
        return when (key) {
            "ANGRY", "MAD", "RAGE", "FURIOUS", "IRRITATION" -> Emotion.ANGER
            "FRUSTRATED", "ANNOYED", "ANNOYANCE", "IRRITATED", "EXASPERATION" -> Emotion.FRUSTRATION
            "ANXIOUS", "WORRY", "WORRIED", "FEAR", "NERVOUS", "STRESS", "STRESSED", "PANIC" -> Emotion.ANXIETY
            "SAD", "DISAPPOINTED", "DISAPPOINTMENT", "HURT", "UPSET" -> Emotion.SADNESS
            "HAPPY", "HAPPINESS", "JOYFUL", "EXCITED", "EXCITEMENT", "DELIGHT" -> Emotion.JOY
            "GRATEFUL", "THANKFUL", "THANKS", "APPRECIATION" -> Emotion.GRATITUDE
            "RELAXED", "CONTENT", "PEACEFUL", "SERENE" -> Emotion.CALM
            "NONE", "INDIFFERENT" -> Emotion.NEUTRAL
            else -> null
        }
    }

    fun mapTone(label: String?): Tone? {
        val key = normalize(label) ?: return null
        Tone.entries.firstOrNull { it.name == key }?.let { return it }
        return when (key) {
            "PASSIVEAGGRESSIVE", "PASSIVE_AGRESSIVE", "SARCASTIC", "SNARKY" -> Tone.PASSIVE_AGGRESSIVE
            "AGGRESSIVE", "ANGRY", "HOSTILITY", "RUDE", "HARSH", "THREATENING" -> Tone.HOSTILE
            "TERSE", "BLUNT", "SHORT", "CLIPPED", "DISMISSIVE" -> Tone.CURT
            "PRESSING", "DEMANDING", "PRESSURED" -> Tone.URGENT
            "PROFESSIONAL", "POLITE" -> Tone.FORMAL
            "CASUAL", "PLAIN", "MATTER_OF_FACT", "NONE" -> Tone.NEUTRAL
            "CHEERFUL", "UPBEAT", "POSITIVE" -> Tone.FRIENDLY
            "AFFECTIONATE", "KIND", "CARING", "EMPATHETIC", "SUPPORTIVE" -> Tone.WARM
            else -> null
        }
    }

    /** Upper-cases and turns spaces / hyphens into underscores: "passive-aggressive" -> "PASSIVE_AGGRESSIVE". */
    private fun normalize(label: String?): String? {
        val cleaned = label
            ?.trim()
            ?.trim('"', '\'', '.', '<', '>')
            ?.uppercase()
            ?.replace(Regex("[\\s\\-]+"), "_")
            ?.trim('_')
        return cleaned?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- JSON extraction

    private class Fields(
        val emotion: Emotion?,
        val tone: Tone?,
        val intensity: Int?,
        val rationale: String?,
    )

    private fun extractFromJson(raw: String): Fields? {
        val candidate = firstJsonObject(raw) ?: return null
        val obj = try {
            JSONObject(candidate)
        } catch (_: Exception) {
            return null
        }
        val byKey = HashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            byKey[k.lowercase()] = obj.opt(k)
        }
        return Fields(
            emotion = mapEmotion(byKey["emotion"]?.takeUnless { it == JSONObject.NULL }?.toString()),
            tone = mapTone(byKey["tone"]?.takeUnless { it == JSONObject.NULL }?.toString()),
            intensity = toIntensity(byKey["intensity"]),
            rationale = (byKey["rationale"] as? String) ?: (byKey["reason"] as? String),
        )
    }

    /**
     * Returns the first balanced `{...}` in [raw], respecting quoted strings. If the object is cut off
     * (generation cap hit mid-object) it is closed so org.json still gets a chance at it.
     */
    internal fun firstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        // Truncated: close the open string and every open brace.
        val sb = StringBuilder(raw.substring(start).trimEnd().removeSuffix(","))
        if (inString) sb.append('"')
        repeat(depth.coerceAtLeast(1)) { sb.append('}') }
        return sb.toString()
    }

    private fun toIntensity(value: Any?): Int? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toDouble().takeIf { !it.isNaN() }?.roundToInt()
        else -> Regex("-?\\d+(\\.\\d+)?").find(value.toString())?.value?.toDoubleOrNull()?.roundToInt()
    }

    // ---------------------------------------------------------------- regex extraction

    private val EMOTION_RE = Regex("\"?emotion\"?\\s*[:=]\\s*\"?([A-Za-z][A-Za-z _-]*)", RegexOption.IGNORE_CASE)
    private val TONE_RE = Regex("\"?tone\"?\\s*[:=]\\s*\"?([A-Za-z][A-Za-z _-]*)", RegexOption.IGNORE_CASE)
    private val INTENSITY_RE = Regex("\"?intensity\"?\\s*[:=]\\s*\"?(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val RATIONALE_RE = Regex("\"?rationale\"?\\s*[:=]\\s*\"((?:[^\"\\\\]|\\\\.)*)", RegexOption.IGNORE_CASE)

    private fun extractWithRegex(raw: String): Fields? {
        val emotion = EMOTION_RE.find(raw)?.groupValues?.get(1)?.let(::mapEmotion)
        val tone = TONE_RE.find(raw)?.groupValues?.get(1)?.let(::mapTone)
        if (emotion == null && tone == null) return null
        return Fields(
            emotion = emotion,
            tone = tone,
            intensity = INTENSITY_RE.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt(),
            rationale = RATIONALE_RE.find(raw)?.groupValues?.get(1),
        )
    }

    private fun defaultIntensity(emotion: Emotion, tone: Tone): Int = when {
        tone == Tone.HOSTILE -> 7
        tone == Tone.PASSIVE_AGGRESSIVE -> 5
        emotion == Emotion.NEUTRAL && tone == Tone.NEUTRAL -> 1
        else -> 3
    }

    // ---------------------------------------------------------------- keyword heuristic

    private val HOSTILE_WORDS = listOf(
        "unacceptable", "ridiculous", "incompetent", "stupid", "idiot", "pathetic", "useless",
        "furious", "outrageous", "disgusting", "absurd", "hate", "wtf", "damn", "shut up",
        "how dare", "sick of", "fed up", "last straw", "or else", "lawyer",
    )
    private val PASSIVE_AGGRESSIVE_PHRASES = listOf(
        "per my last email", "per my previous email", "as previously stated", "as i already said",
        "as i said before", "as mentioned before", "friendly reminder", "gentle reminder",
        "not sure if you saw", "just circling back again", "as you know",
        "with all due respect", "must be nice", "i guess",
    )
    private val ANXIETY_WORDS = listOf(
        "worried", "nervous", "anxious", "concerned", "afraid", "panic", "stressed", "scared",
        "urgent", "asap", "deadline", "final notice", "running out of time",
    )
    private val GRATITUDE_WORDS = listOf(
        "thank you so much", "really appreciate", "so grateful", "grateful", "thanks so much", "much appreciated",
    )
    private val JOY_WORDS = listOf("congrats", "congratulations", "excited", "thrilled", "great news", "awesome", "love it")
    private val SADNESS_WORDS = listOf("disappointed", "sad", "heartbroken", "let down", "sorry to hear", "unfortunately")
    private val ABSOLUTES = Regex("\\b(always|never|every single time|nobody|nothing ever)\\b", RegexOption.IGNORE_CASE)
    private val EXCLAMATION_RUN = Regex("[!?]*![!?]+")
    private val WORD = Regex("[A-Za-z]{3,}")

    /** Whole-word / whole-phrase matches only, so "hate" does not fire on "whatever". */
    private fun List<String>.hitsIn(lower: String): List<String> =
        filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(lower) }

    /** Best-effort reading from surface signals. Used when the model output is unusable. */
    fun heuristic(text: String): MoodReading {
        val lower = text.lowercase()
        val words = WORD.findAll(text).map { it.value }.toList()
        val capsWords = words.count { w -> w.all { it.isUpperCase() } }
        val capsRatio = if (words.isEmpty()) 0.0 else capsWords.toDouble() / words.size
        val shouting = capsWords >= 2 && capsRatio >= 0.15
        val exclRuns = EXCLAMATION_RUN.findAll(text).count()
        val absolutes = ABSOLUTES.findAll(text).count()
        val hostileHits = HOSTILE_WORDS.hitsIn(lower)
        val paHits = PASSIVE_AGGRESSIVE_PHRASES.hitsIn(lower)
        val anxietyHits = ANXIETY_WORDS.hitsIn(lower)
        val gratitudeHits = GRATITUDE_WORDS.hitsIn(lower)
        val joyHits = JOY_WORDS.hitsIn(lower)
        val sadHits = SADNESS_WORDS.hitsIn(lower)

        val signals = buildList {
            if (shouting) add("ALL CAPS")
            if (exclRuns > 0) add("'!!' runs")
            if (absolutes > 0) add("absolutes")
            hostileHits.take(2).forEach { add("'$it'") }
            paHits.take(2).forEach { add("'$it'") }
        }
        val heat = hostileHits.size * 2 + (if (shouting) 2 else 0) + exclRuns.coerceAtMost(2) + absolutes.coerceAtMost(2)

        val (emotion, tone, intensity) = when {
            (hostileHits.isNotEmpty() && heat >= 3) || heat >= 5 ->
                Triple(Emotion.ANGER, Tone.HOSTILE, (4 + heat).coerceIn(6, 10))
            paHits.isNotEmpty() ->
                Triple(Emotion.FRUSTRATION, Tone.PASSIVE_AGGRESSIVE, (5 + heat).coerceIn(5, 8))
            hostileHits.isNotEmpty() || shouting || exclRuns >= 2 ->
                Triple(Emotion.FRUSTRATION, if (shouting) Tone.HOSTILE else Tone.CURT, (3 + heat).coerceIn(4, 8))
            anxietyHits.isNotEmpty() ->
                Triple(Emotion.ANXIETY, Tone.URGENT, (3 + anxietyHits.size).coerceIn(3, 7))
            sadHits.isNotEmpty() ->
                Triple(Emotion.SADNESS, Tone.NEUTRAL, (2 + sadHits.size).coerceIn(3, 6))
            gratitudeHits.isNotEmpty() ->
                Triple(Emotion.GRATITUDE, Tone.WARM, 4)
            joyHits.isNotEmpty() ->
                Triple(Emotion.JOY, Tone.FRIENDLY, 4)
            else -> Triple(Emotion.NEUTRAL, Tone.NEUTRAL, 1)
        }
        val because = (signals + anxietyHits.take(1).map { "'$it'" }).joinToString(", ")
        return MoodReading(
            emotion = emotion,
            tone = tone,
            intensity = intensity,
            heated = isHeated(emotion, tone, intensity),
            rationale = if (because.isEmpty()) "Keyword fallback: no strong signals found."
            else "Keyword fallback: $because.",
        )
    }
}
