package com.jerush.moodmail.llm

import com.jerush.moodmail.model.Email

/**
 * Prompt text for the on-device LFM2.5-1.2B tone reader.
 *
 * Melange 1.11.0 has no system-prompt parameter for text-only models: `ZeticMLangeLLMModel.run(text)`
 * wraps `text` in the model's chat template as a single user turn. So the system prompt is plain prose
 * prepended to the user content. Do not add chat-template tokens here; the SDK already does that.
 *
 * Every section is a separate value so it can be tuned without touching the builders.
 */
object MoodPrompts {

    const val MAX_BODY_CHARS = 1500
    const val MAX_REPLIED_TO_CHARS = 600
    const val MAX_SUBJECT_CHARS = 160

    const val ROLE_EMAIL =
        "You are MoodMail, a sharp and calibrated reader of emotional tone in email. " +
            "You read what the SENDER feels and how they come across to the recipient. " +
            "Judge the words on the page, not the topic: bad news delivered kindly is not hostile, " +
            "and a polite surface over a jab is not friendly."

    const val ROLE_DRAFT =
        "You are MoodMail, a sharp and calibrated reader of emotional tone in email. " +
            "You are reviewing a DRAFT REPLY the user has not sent yet. Read how the draft will land " +
            "with its recipient, as an honest friend would before they hit send. " +
            "If the email being replied to is shown, also judge whether the draft escalates it: " +
            "matching or raising its heat, adding blame, sarcasm, or ultimatums counts as escalation; " +
            "a calm, factual reply to an angry email does not."

    const val LABELS =
        "EMOTION (pick exactly one):\n" +
            "ANGER: open anger, outrage, or attack.\n" +
            "FRUSTRATION: fed up or exasperated, but not attacking.\n" +
            "ANXIETY: worry, fear, stress, pressure about an outcome.\n" +
            "SADNESS: disappointment, hurt, loss.\n" +
            "NEUTRAL: no clear feeling, routine information.\n" +
            "CALM: settled and relaxed, actively reassuring.\n" +
            "JOY: happy, excited, celebrating.\n" +
            "GRATITUDE: thankful, appreciative.\n" +
            "\n" +
            "TONE (pick exactly one):\n" +
            "HOSTILE: openly aggressive, insulting, threatening, or shouting.\n" +
            "PASSIVE_AGGRESSIVE: polite surface with a hidden jab: sarcasm, \"per my last email\", pointed reminders.\n" +
            "CURT: short and clipped with warmth removed; NEUTRAL is short but simply efficient.\n" +
            "URGENT: pushes for speed: deadlines, ASAP, escalation of priority.\n" +
            "FORMAL: professional and polite with no hidden edge; PASSIVE_AGGRESSIVE hides an edge.\n" +
            "NEUTRAL: plain and matter-of-fact.\n" +
            "FRIENDLY: casual, upbeat, pleasant.\n" +
            "WARM: caring and personal, goes out of its way to be kind."

    const val INTENSITY =
        "INTENSITY (integer 0 to 10, how strongly the feeling comes through):\n" +
            "0 = no feeling at all, pure logistics.\n" +
            "3 = mild, noticeable on a careful read.\n" +
            "6 = clear and strong, any reader would feel it.\n" +
            "8 = very strong: shouting, threats, or open distress.\n" +
            "10 = extreme, out of control."

    const val HEATED_RULE =
        "HEATED is true if intensity >= 6 AND emotion is ANGER, FRUSTRATION, ANXIETY, or SADNESS; " +
            "or if tone is HOSTILE or PASSIVE_AGGRESSIVE. Otherwise HEATED is false."

    const val SIGNALS =
        "SIGNALS to weigh: ALL CAPS words; runs of exclamation marks (!!, !!!, ?!); absolutes " +
            "(\"always\", \"never\", \"every time\", \"nobody\"); sarcasm markers (\"great job\" after a failure, " +
            "\"thanks so much\" for nothing, scare quotes, \"I guess\"); blame language (\"you failed\", " +
            "\"your mistake\", \"you didn't\"); deadline pressure (\"by EOD\", \"ASAP\", \"final notice\"); " +
            "terse sign-offs (\"Thanks.\", \"Regards.\", a bare name, or no greeting at all). " +
            "One signal alone is weak evidence; several together are strong."

    const val EXAMPLES =
        "EXAMPLES:\n" +
            "Text: \"Hi Sam, attaching the Q3 deck for Thursday. Let me know if anything needs a tweak. Cheers, Priya\"\n" +
            "{\"emotion\":\"NEUTRAL\",\"tone\":\"FRIENDLY\",\"intensity\":1,\"heated\":false,\"rationale\":\"Routine handoff with a friendly sign-off.\"}\n" +
            "Text: \"Per my last email, the numbers were due Friday. Not sure if you saw it. Thanks.\"\n" +
            "{\"emotion\":\"FRUSTRATION\",\"tone\":\"PASSIVE_AGGRESSIVE\",\"intensity\":5,\"heated\":true,\"rationale\":\"'Per my last email' and 'not sure if you saw it' are pointed jabs; terse 'Thanks.'\"}\n" +
            "Text: \"This is UNACCEPTABLE. You ALWAYS miss the deadline and I am done covering for you!!!\"\n" +
            "{\"emotion\":\"ANGER\",\"tone\":\"HOSTILE\",\"intensity\":9,\"heated\":true,\"rationale\":\"All caps, 'always', blame, and an exclamation run.\"}\n" +
            "Text: \"Sorry to bother you again, the client call is in an hour and I still don't have the contract. Is it coming? I'm really worried.\"\n" +
            "{\"emotion\":\"ANXIETY\",\"tone\":\"URGENT\",\"intensity\":6,\"heated\":true,\"rationale\":\"Time pressure and explicit worry about a missing contract.\"}"

    const val OUTPUT_FORMAT =
        "OUTPUT: reply with ONE line of JSON and nothing else. No code fences, no prose before or after. " +
            "Exact shape: {\"emotion\":\"...\",\"tone\":\"...\",\"intensity\":N,\"heated\":true|false,\"rationale\":\"...\"} " +
            "Use only the labels listed above, in capitals. The rationale is one short sentence that names the signals you saw."

    private const val SHARED_BODY =
        LABELS + "\n\n" + INTENSITY + "\n\n" + HEATED_RULE + "\n\n" + SIGNALS + "\n\n" + EXAMPLES + "\n\n" + OUTPUT_FORMAT

    const val EMAIL_SYSTEM_PROMPT = ROLE_EMAIL + "\n\n" + SHARED_BODY

    const val DRAFT_SYSTEM_PROMPT =
        ROLE_DRAFT + "\n\n" + SHARED_BODY + "\n\n" +
            "For a draft that escalates the email it replies to, raise intensity by at least 1 and say so in the rationale."

    const val CLOSING_INSTRUCTION = "Respond with the JSON line only."

    fun buildEmailPrompt(email: Email): String = buildString {
        append(EMAIL_SYSTEM_PROMPT)
        append("\n\n---\nINCOMING EMAIL\n")
        append("From: ").append(oneLine(email.from)).append('\n')
        append("Subject: ").append(oneLine(email.subject).take(MAX_SUBJECT_CHARS)).append('\n')
        append("Body:\n")
        append(prepareBody(email.body.ifBlank { email.snippet }, MAX_BODY_CHARS))
        append("\n---\n")
        append(CLOSING_INSTRUCTION)
    }

    fun buildDraftPrompt(draft: String, replyingTo: Email?): String = buildString {
        append(DRAFT_SYSTEM_PROMPT)
        append("\n\n---\n")
        if (replyingTo != null) {
            append("EMAIL BEING REPLIED TO (context only, do not rate it)\n")
            append("From: ").append(oneLine(replyingTo.from)).append('\n')
            append("Subject: ").append(oneLine(replyingTo.subject).take(MAX_SUBJECT_CHARS)).append('\n')
            append(prepareBody(replyingTo.body.ifBlank { replyingTo.snippet }, MAX_REPLIED_TO_CHARS))
            append("\n---\n")
        }
        append("DRAFT REPLY TO RATE\n")
        append(prepareBody(draft, MAX_BODY_CHARS))
        append("\n---\n")
        append(CLOSING_INSTRUCTION)
    }

    /** Strips quoted reply chains, collapses blank runs, and truncates to [maxChars]. */
    fun prepareBody(text: String, maxChars: Int): String {
        val stripped = stripQuoted(text)
            .replace(Regex("\r\n?"), "\n")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        if (stripped.length <= maxChars) return stripped
        val cut = stripped.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > maxChars * 0.8) cut.substring(0, lastSpace) else cut).trimEnd() + " [...]"
    }

    // "On Tue, 3 Oct 2026 at 10:12, Ana <ana@x.com> wrote:" possibly wrapped over two lines by the client.
    private val ATTRIBUTION_LINE = Regex("(?m)^\\s*On\\s[^\\n]{0,200}(?:\\n[^\\n]{0,200})?\\swrote:\\s*$")
    private val FORWARD_OR_ORIGINAL = Regex("(?m)^\\s*-{2,}\\s*(Original Message|Forwarded message)\\s*-{2,}.*$", RegexOption.IGNORE_CASE)

    /** Removes everything after an "On ... wrote:" attribution and every line starting with ">". */
    fun stripQuoted(text: String): String {
        var body = text.replace(Regex("\r\n?"), "\n")
        ATTRIBUTION_LINE.find(body)?.let { body = body.substring(0, it.range.first) }
        FORWARD_OR_ORIGINAL.find(body)?.let { body = body.substring(0, it.range.first) }
        return body.lineSequence()
            .filterNot { it.trimStart().startsWith(">") }
            .joinToString("\n")
            .trimEnd()
    }

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
