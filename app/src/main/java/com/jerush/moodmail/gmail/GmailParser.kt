package com.jerush.moodmail.gmail

import com.jerush.moodmail.model.Email
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Turns a Gmail API `users.messages.get?format=full` response into an [Email].
 *
 * Pure Kotlin plus org.json, no Android APIs, so it runs in plain JVM unit tests.
 */
object GmailParser {

    fun parseMessage(json: JSONObject): Email {
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = payload.optJSONArray("headers")
        val snippet = unescapeHtml(json.optString("snippet", ""))

        val plain = findPart(payload, "text/plain")?.let(::decodePart)?.trim()
        val body = when {
            !plain.isNullOrBlank() -> plain
            else -> findPart(payload, "text/html")?.let(::decodePart)?.let(::stripHtml)
                ?.takeIf { it.isNotBlank() }
                ?: snippet
        }

        val sentAt = json.optString("internalDate", "").trim().toLongOrNull()
            ?.let(Instant::ofEpochMilli)
            ?: parseDateHeader(header(headers, "Date"))
            ?: Instant.EPOCH

        return Email(
            id = json.optString("id", ""),
            threadId = json.optString("threadId", ""),
            from = header(headers, "From").orEmpty(),
            subject = header(headers, "Subject").orEmpty(),
            snippet = snippet,
            body = body,
            sentAt = sentAt,
        )
    }

    /** First header with [name], case-insensitive. */
    private fun header(headers: JSONArray?, name: String): String? {
        if (headers == null) return null
        for (i in 0 until headers.length()) {
            val h = headers.optJSONObject(i) ?: continue
            if (h.optString("name").equals(name, ignoreCase = true)) return h.optString("value")
        }
        return null
    }

    /**
     * Depth-first search for the first inline part of [mimeType] that carries body data.
     * Parts with a filename are attachments and are skipped.
     */
    private fun findPart(part: JSONObject, mimeType: String): JSONObject? {
        val isAttachment = part.optString("filename", "").isNotEmpty()
        val data = part.optJSONObject("body")?.optString("data", "").orEmpty()
        if (!isAttachment && part.optString("mimeType").equals(mimeType, ignoreCase = true) && data.isNotEmpty()) {
            return part
        }
        val children = part.optJSONArray("parts") ?: return null
        for (i in 0 until children.length()) {
            val child = children.optJSONObject(i) ?: continue
            findPart(child, mimeType)?.let { return it }
        }
        return null
    }

    private fun decodePart(part: JSONObject): String {
        val data = part.optJSONObject("body")?.optString("data", "").orEmpty()
        val bytes = decodeBase64Url(data)
        return String(bytes, charsetOf(part))
    }

    /** Gmail uses the URL-safe alphabet and often drops padding. */
    internal fun decodeBase64Url(data: String): ByteArray {
        val normalized = data.filterNot { it.isWhitespace() }
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return Base64.getUrlDecoder().decode(padded)
    }

    private val charsetParam = Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE)

    private fun charsetOf(part: JSONObject): Charset {
        val contentType = header(part.optJSONArray("headers"), "Content-Type") ?: return Charsets.UTF_8
        val name = charsetParam.find(contentType)?.groupValues?.get(1) ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: IllegalArgumentException) {
            Charsets.UTF_8
        }
    }

    private val trailingComment = Regex("""\s*\([^)]*\)\s*$""")

    /** RFC 1123 / RFC 2822 Date header, tolerating a trailing "(UTC)" style comment. */
    internal fun parseDateHeader(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(trailingComment, "").trim().replace(Regex("""\s+"""), " ")
        return try {
            ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private val dropBlocks = Regex("""<(script|style|head)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val lineBreaks = Regex("""<\s*(br|/p|/div|/li|/tr|/h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""<[^>]*>""")
    private val comments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val runsOfSpace = Regex("""[ \t]+""")
    private val manyBlankLines = Regex("""\n{3,}""")

    /** Crude HTML to text. Good enough for tone analysis, not for display. */
    internal fun stripHtml(html: String): String {
        val text = html
            .replace(comments, "")
            .replace(dropBlocks, "")
            .replace(lineBreaks, "\n")
            .replace(anyTag, "")
        return unescapeHtml(text)
            .replace(' ', ' ')
            .lines()
            .map { it.replace(runsOfSpace, " ").trim() }
            .joinToString("\n")
            .replace(manyBlankLines, "\n\n")
            .trim()
    }

    private val entity = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|quot|apos|lt|gt|nbsp|amp);""")

    /**
     * Unescapes the entities Gmail puts in snippets plus numeric references, in a single pass
     * so `&amp;lt;` becomes `&lt;` rather than `<`.
     */
    internal fun unescapeHtml(s: String): String {
        if (!s.contains('&')) return s
        return s.replace(entity) { m ->
            when (val v = m.groupValues[1]) {
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                "amp" -> "&"
                else -> {
                    val code = if (v[1] == 'x' || v[1] == 'X') v.substring(2).toIntOrNull(16) else v.substring(1).toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
                }
            }
        }
    }
}
