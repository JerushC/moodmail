package com.jerush.moodmail.vision

import android.graphics.Bitmap

data class ProbeResult(val description: String, val latencyMs: Long)

/** Experimental: asks the LFM2.5-VL-450M model to describe the user's expression in one frame. */
interface FaceMoodProbe {
    suspend fun warmUp(onProgress: (Float) -> Unit = {})
    suspend fun describe(frame: Bitmap): ProbeResult
    fun close()
}
