package com.jerush.moodmail.gmail

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class GmailParserTest {

    // --- fixture helpers -------------------------------------------------------------------

    private fun b64url(text: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun headers(vararg pairs: Pair<String, String>): JSONArray =
        JSONArray().apply { pairs.forEach { (n, v) -> put(JSONObject().put("name", n).put("value", v)) } }

    private fun leaf(mimeType: String, data: String?, filename: String = "", partHeaders: JSONArray = JSONArray()): JSONObject {
        val body = JSONObject().put("size", data?.length ?: 0)
        if (data != null) body.put("data", data)
        return JSONObject()
            .put("mimeType", mimeType)
            .put("filename", filename)
            .put("headers", partHeaders)
            .put("body", body)
    }

    private fun multipart(mimeType: String, vararg parts: JSONObject, partHeaders: JSONArray = JSONArray()): JSONObject =
        JSONObject()
            .put("mimeType", mimeType)
            .put("filename", "")
            .put("headers", partHeaders)
            .put("body", JSONObject().put("size", 0))
            .put("parts", JSONArray().apply { parts.forEach { put(it) } })

    private fun message(payload: JSONObject, snippet: String = "", internalDate: String? = "1700000000000"): JSONObject =
        JSONObject()
            .put("id", "msg-1")
            .put("threadId", "thr-1")
            .put("snippet", snippet)
            .apply { if (internalDate != null) put("internalDate", internalDate) }
            .put("payload", payload)

    private val stdHeaders = arrayOf(
        "From" to "Alex Boss <alex@example.com>",
        "Subject" to "Where is the report?",
    )

    // --- tests -----------------------------------------------------------------------------

    @Test
    fun singlePartTextPlain() {
        val payload = leaf("text/plain", b64url("I need this by 5pm. No excuses."))
            .put("headers", headers(*stdHeaders))
        val email = GmailParser.parseMessage(message(payload, snippet = "I need this by 5pm."))

        assertEquals("msg-1", email.id)
        assertEquals("thr-1", email.threadId)
        assertEquals("Alex Boss <alex@example.com>", email.from)
        assertEquals("Where is the report?", email.subject)
        assertEquals("I need this by 5pm. No excuses.", email.body)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), email.sentAt)
    }

    @Test
    fun headerLookupIsCaseInsensitive() {
        val payload = leaf("text/plain", b64url("hi"))
            .put("headers", headers("from" to "a@example.com", "SUBJECT" to "lower and upper"))
        val email = GmailParser.parseMessage(message(payload))

        assertEquals("a@example.com", email.from)
        assertEquals("lower and upper", email.subject)
    }

    @Test
    fun nestedMultipartAlternativeInsideMixedPrefersPlainText() {
        // html is listed first inside the alternative, and a text/plain attachment sits beside it,
        // so a parser that takes the first part or ignores filenames gets the wrong answer.
        val alternative = multipart(
            "multipart/alternative",
            leaf("text/html", b64url("<p>HTML version</p>")),
            leaf("text/plain", b64url("Plain version of the body")),
        )
        val payload = multipart(
            "multipart/mixed",
            leaf("text/plain", b64url("attached notes, not the body"), filename = "notes.txt"),
            alternative,
            leaf("application/pdf", null, filename = "report.pdf"),
            partHeaders = headers(*stdHeaders),
        )
        val email = GmailParser.parseMessage(message(payload, snippet = "Plain version"))

        assertEquals("Plain version of the body", email.body)
        assertEquals("Where is the report?", email.subject)
    }

    @Test
    fun htmlOnlyIsStrippedToText() {
        val html = """
            <html><head><title>ignored title</title><style>p { color: red; }</style></head>
            <body><p>Hi&nbsp;team,</p><p>This is <b>completely</b> unacceptable &amp; needs fixing.<br>Thanks</p>
            <!-- tracking comment --><script>track()</script></body></html>
        """.trimIndent()
        val payload = multipart(
            "multipart/alternative",
            leaf("text/html", b64url(html)),
            partHeaders = headers(*stdHeaders),
        )
        val body = GmailParser.parseMessage(message(payload)).body

        assertFalse("tags should be gone: $body", body.contains('<') || body.contains('>'))
        assertFalse("style content should be gone: $body", body.contains("color"))
        assertFalse("script content should be gone: $body", body.contains("track()"))
        assertFalse("head title should be gone: $body", body.contains("ignored title"))
        assertTrue(body.contains("Hi team,"))
        assertTrue(body.contains("This is completely unacceptable & needs fixing."))
        assertTrue("br should become a line break: $body", body.contains("fixing.\nThanks"))
    }

    @Test
    fun missingBodyFallsBackToUnescapedSnippet() {
        val payload = multipart(
            "multipart/mixed",
            leaf("text/plain", null), // size 0, no data
            leaf("image/png", null, filename = "photo.png"),
            partHeaders = headers(*stdHeaders),
        )
        val email = GmailParser.parseMessage(
            message(payload, snippet = "It&#39;s &quot;fine&quot; &amp; I&#x27;m &lt;calm&gt;")
        )

        val expected = "It's \"fine\" & I'm <calm>"
        assertEquals(expected, email.snippet)
        assertEquals(expected, email.body)
    }

    @Test
    fun snippetUnescapeIsSinglePass() {
        val email = GmailParser.parseMessage(message(leaf("text/plain", null), snippet = "a &amp;lt; b"))
        assertEquals("a &lt; b", email.snippet)
    }

    @Test
    fun base64UrlWithoutPaddingAndUrlSafeAlphabet() {
        // "Hi?>> ok" is "SGk/Pj4gb2s=" in standard base64. Gmail sends "SGk_Pj4gb2s":
        // '_' instead of '/', and the '=' dropped. A standard decoder rejects both.
        val payload = leaf("text/plain", "SGk_Pj4gb2s").put("headers", headers(*stdHeaders))
        assertEquals("Hi?>> ok", GmailParser.parseMessage(message(payload)).body)

        // Multi-byte UTF-8, also unpadded.
        val accents = leaf("text/plain", "Q2Fmw6kg4oCUIGxhdGVyPz4")
        assertEquals("Café — later?>", GmailParser.parseMessage(message(accents)).body)
    }

    @Test
    fun honoursPartCharset() {
        val latin1 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Déjà vu".toByteArray(Charsets.ISO_8859_1))
        val part = leaf(
            "text/plain",
            latin1,
            partHeaders = headers("Content-Type" to "text/plain; charset=\"ISO-8859-1\""),
        )
        assertEquals("Déjà vu", GmailParser.parseMessage(message(part)).body)
    }

    @Test
    fun missingInternalDateFallsBackToDateHeader() {
        val payload = leaf("text/plain", b64url("body"))
            .put("headers", headers(*stdHeaders, "Date" to "Tue, 15 Nov 1994 08:12:31 GMT"))
        val email = GmailParser.parseMessage(message(payload, internalDate = null))

        assertEquals(Instant.parse("1994-11-15T08:12:31Z"), email.sentAt)
    }

    @Test
    fun dateHeaderWithOffsetAndTrailingComment() {
        val payload = leaf("text/plain", b64url("body"))
            .put("headers", headers("Date" to "Tue, 6 Oct 2026 21:30:00 +0530 (IST)"))
        val email = GmailParser.parseMessage(message(payload, internalDate = null))

        assertEquals(Instant.parse("2026-10-06T16:00:00Z"), email.sentAt)
    }

    @Test
    fun internalDateWinsOverDateHeader() {
        val payload = leaf("text/plain", b64url("body"))
            .put("headers", headers("Date" to "Tue, 15 Nov 1994 08:12:31 GMT"))
        val email = GmailParser.parseMessage(message(payload, internalDate = "1700000000000"))

        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), email.sentAt)
    }
}
