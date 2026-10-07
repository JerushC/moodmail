package com.jerush.moodmail.llm

import com.jerush.moodmail.model.Emotion
import com.jerush.moodmail.model.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodParserTest {

    // ---------------------------------------------------------------- clean and wrapped JSON

    @Test
    fun cleanJson_parsesEveryField() {
        val r = MoodParser.parse(
            """{"emotion":"ANGER","tone":"HOSTILE","intensity":8,"heated":true,"rationale":"All caps and blame."}""",
        )
        assertEquals(Emotion.ANGER, r.emotion)
        assertEquals(Tone.HOSTILE, r.tone)
        assertEquals(8, r.intensity)
        assertTrue(r.heated)
        assertEquals("All caps and blame.", r.rationale)
    }

    @Test
    fun fencedJson_isUnwrapped() {
        val raw = "```json\n" +
            """{"emotion":"anxiety","tone":"urgent","intensity":7,"heated":true,"rationale":"Deadline in an hour."}""" +
            "\n```"
        val r = MoodParser.parse(raw)
        assertEquals(Emotion.ANXIETY, r.emotion)
        assertEquals(Tone.URGENT, r.tone)
        assertEquals(7, r.intensity)
        assertTrue(r.heated)
    }

    @Test
    fun proseAroundJson_extractsFirstObject() {
        val raw = "Sure! Here is my analysis of the email:\n" +
            """{"emotion":"GRATITUDE","tone":"WARM","intensity":4,"heated":false,"rationale":"Thanks the team warmly."}""" +
            "\nLet me know if you need anything else. {\"emotion\":\"ANGER\"}"
        val r = MoodParser.parse(raw)
        assertEquals(Emotion.GRATITUDE, r.emotion)
        assertEquals(Tone.WARM, r.tone)
        assertFalse(r.heated)
    }

    @Test
    fun braceInsideRationaleString_doesNotEndObjectEarly() {
        val r = MoodParser.parse(
            """{"emotion":"JOY","tone":"FRIENDLY","intensity":4,"heated":false,"rationale":"Ends with a smiley :} and a wave"}""",
        )
        assertEquals(Emotion.JOY, r.emotion)
        assertEquals("Ends with a smiley :} and a wave", r.rationale)
    }

    @Test
    fun keysAndLabels_areCaseInsensitive() {
        val r = MoodParser.parse("""{"Emotion":"Frustration","TONE":"Curt","Intensity":4,"rationale":"Clipped."}""")
        assertEquals(Emotion.FRUSTRATION, r.emotion)
        assertEquals(Tone.CURT, r.tone)
        assertEquals(4, r.intensity)
    }

    @Test
    fun truncatedObject_isClosedAndParsed() {
        val r = MoodParser.parse("""{"emotion":"FRUSTRATION","tone":"CURT","intensity":4,"rationale":"Short and""")
        assertEquals(Emotion.FRUSTRATION, r.emotion)
        assertEquals(Tone.CURT, r.tone)
        assertEquals(4, r.intensity)
    }

    @Test
    fun malformedJson_fallsBackToFieldRegex() {
        val r = MoodParser.parse("Emotion: frustrated\nTone: curt\nIntensity: 6")
        assertEquals(Emotion.FRUSTRATION, r.emotion)
        assertEquals(Tone.CURT, r.tone)
        assertEquals(6, r.intensity)
        assertTrue(r.heated)
    }

    // ---------------------------------------------------------------- label variants

    @Test
    fun hyphenatedPassiveAggressive_maps() {
        val r = MoodParser.parse(
            """{"emotion":"frustration","tone":"passive-aggressive","intensity":5,"heated":true,"rationale":"Per my last email."}""",
        )
        assertEquals(Tone.PASSIVE_AGGRESSIVE, r.tone)
    }

    @Test
    fun spacedAndMixedCasePassiveAggressive_maps() {
        assertEquals(Tone.PASSIVE_AGGRESSIVE, MoodParser.mapTone("Passive Aggressive"))
        assertEquals(Tone.PASSIVE_AGGRESSIVE, MoodParser.mapTone("passive_aggressive"))
        assertEquals(Tone.PASSIVE_AGGRESSIVE, MoodParser.mapTone(" PASSIVE-AGGRESSIVE "))
    }

    @Test
    fun synonyms_mapToEnumValues() {
        assertEquals(Emotion.ANGER, MoodParser.mapEmotion("angry"))
        assertEquals(Emotion.ANXIETY, MoodParser.mapEmotion("worried"))
        assertEquals(Tone.CURT, MoodParser.mapTone("terse"))
        assertEquals(null, MoodParser.mapTone("wistful"))
    }

    // ---------------------------------------------------------------- intensity clamping

    @Test
    fun intensityAboveRange_clampsToTen() {
        val r = MoodParser.parse("""{"emotion":"ANGER","tone":"HOSTILE","intensity":15,"heated":true,"rationale":"x"}""")
        assertEquals(10, r.intensity)
    }

    @Test
    fun intensityBelowRange_clampsToZero() {
        val r = MoodParser.parse("""{"emotion":"CALM","tone":"NEUTRAL","intensity":-4,"heated":false,"rationale":"x"}""")
        assertEquals(0, r.intensity)
        assertFalse(r.heated)
    }

    @Test
    fun intensityAsFractionOrString_isRead() {
        assertEquals(7, MoodParser.parse("""{"emotion":"ANGER","tone":"CURT","intensity":6.6}""").intensity)
        assertEquals(8, MoodParser.parse("""{"emotion":"ANGER","tone":"CURT","intensity":"8/10"}""").intensity)
    }

    // ---------------------------------------------------------------- heated rule beats the model

    @Test
    fun heatedTrueOnCalmReading_isOverriddenToFalse() {
        val r = MoodParser.parse("""{"emotion":"CALM","tone":"FRIENDLY","intensity":2,"heated":true,"rationale":"x"}""")
        assertFalse(r.heated)
    }

    @Test
    fun heatedFalseOnStrongNegative_isOverriddenToTrue() {
        val r = MoodParser.parse("""{"emotion":"FRUSTRATION","tone":"NEUTRAL","intensity":7,"heated":false,"rationale":"x"}""")
        assertTrue(r.heated)
    }

    @Test
    fun heatedFalseOnPassiveAggressiveLowIntensity_isOverriddenToTrue() {
        val r = MoodParser.parse("""{"emotion":"NEUTRAL","tone":"PASSIVE_AGGRESSIVE","intensity":3,"heated":false,"rationale":"x"}""")
        assertTrue(r.heated)
    }

    @Test
    fun heatedRule_boundaryAtSix() {
        assertFalse(MoodParser.isHeated(Emotion.ANXIETY, Tone.URGENT, 5))
        assertTrue(MoodParser.isHeated(Emotion.ANXIETY, Tone.URGENT, 6))
        assertFalse(MoodParser.isHeated(Emotion.JOY, Tone.FRIENDLY, 9))
        assertTrue(MoodParser.isHeated(Emotion.CALM, Tone.HOSTILE, 0))
    }

    // ---------------------------------------------------------------- heuristic fallback

    @Test
    fun garbageShoutingText_fallsBackToHostileHeuristic() {
        val r = MoodParser.parse("THIS IS UNACCEPTABLE!!! You never listen to a word I say")
        assertEquals(Emotion.ANGER, r.emotion)
        assertEquals(Tone.HOSTILE, r.tone)
        assertTrue(r.intensity >= 6)
        assertTrue(r.heated)
        assertTrue(r.rationale.startsWith("Keyword fallback"))
    }

    @Test
    fun garbageModelOutput_usesSourceTextForHeuristic() {
        val r = MoodParser.parse(
            raw = "I'm sorry, I can't help with that.",
            sourceText = "Per my last email, the report was due Friday. Not sure if you saw it.",
        )
        assertEquals(Tone.PASSIVE_AGGRESSIVE, r.tone)
        assertNotEquals(Emotion.NEUTRAL, r.emotion)
        assertTrue(r.heated)
    }

    @Test
    fun unknownLabelsOnly_fallBackToHeuristic() {
        val r = MoodParser.parse(
            raw = """{"emotion":"bemused","tone":"wry","intensity":4}""",
            sourceText = "I'm really worried we will miss the deadline.",
        )
        assertEquals(Emotion.ANXIETY, r.emotion)
        assertEquals(Tone.URGENT, r.tone)
    }

    @Test
    fun blandGarbage_staysNeutralAndCool() {
        val r = MoodParser.parse("lorem ipsum dolor sit amet")
        assertEquals(Emotion.NEUTRAL, r.emotion)
        assertEquals(Tone.NEUTRAL, r.tone)
        assertFalse(r.heated)
    }

    @Test
    fun heuristic_matchesWholeWordsOnly() {
        // "whatever" contains "hate"; it must not read as hostile.
        val r = MoodParser.heuristic("Whatever works for you, see you Monday.")
        assertNotEquals(Tone.HOSTILE, r.tone)
        assertFalse(r.heated)
    }

    @Test
    fun emptyInput_neverThrows() {
        val r = MoodParser.parse("")
        assertEquals(Emotion.NEUTRAL, r.emotion)
        assertFalse(r.heated)
    }
}
