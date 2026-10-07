package com.jerush.moodmail.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.zeticai.mlange.core.model.llm.ZeticMLangeLLMModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Feasibility probe: one camera frame in, one short expression description out, plus wall-clock latency.
 * Uses the only approved vision model, zetic/LFM2.5-VL-450M, via ZeticMLange 1.11.0.
 */
class MelangeFaceMoodProbe(
    private val context: Context,
    private val personalKey: String,
) : FaceMoodProbe {

    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var model: ZeticMLangeLLMModel? = null

    override suspend fun warmUp(onProgress: (Float) -> Unit) {
        loadModel(onProgress)
    }

    override suspend fun describe(frame: Bitmap): ProbeResult {
        val llm = loadModel {}
        return inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                val start = SystemClock.elapsedRealtime()
                val scaled = downscale(frame, MAX_SIDE_PX)
                val image = try {
                    val pixels = IntArray(scaled.width * scaled.height)
                    scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                    ZeticMLangeLLMModel.Image(
                        rgb = bitmapPixelsToRgb(pixels, scaled.width, scaled.height),
                        width = scaled.width,
                        height = scaled.height,
                    )
                } finally {
                    if (scaled !== frame) scaled.recycle()
                }

                // Frames must not share conversation state.
                llm.cleanUp()

                val text = StringBuilder()
                llm.respond(SYSTEM_PROMPT, USER_TEXT, image).collect { token -> text.append(token) }
                ProbeResult(
                    description = text.toString().trim(),
                    latencyMs = SystemClock.elapsedRealtime() - start,
                )
            }
        }
    }

    override fun close() {
        model?.close()
        model = null
    }

    private suspend fun loadModel(onProgress: (Float) -> Unit): ZeticMLangeLLMModel {
        model?.let { return it }
        return initMutex.withLock {
            model ?: withContext(Dispatchers.IO) {
                ZeticMLangeLLMModel(
                    context,
                    personalKey,
                    MODEL_NAME,
                    onDownload = { progress -> onProgress(progress) },
                )
            }.also { model = it }
        }
    }

    private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = maxOf(1, (src.width * ratio).toInt())
        val h = maxOf(1, (src.height * ratio).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private companion object {
        const val MODEL_NAME = "zetic/LFM2.5-VL-450M"
        const val MAX_SIDE_PX = 448
        const val USER_TEXT = "Describe the person's expression."
        const val SYSTEM_PROMPT =
            "Describe the visible facial expression and posture in one sentence. " +
                "Then give exactly one word from: calm, focused, tense, frustrated, upset, tired, happy. " +
                "If no face is visible, say so. Do not guess beyond what is visible."
    }
}

/**
 * Packs ARGB_8888 pixels (the output of Bitmap.getPixels) into row-major RGB bytes,
 * dropping alpha. Output length is width * height * 3.
 */
internal fun bitmapPixelsToRgb(pixels: IntArray, width: Int, height: Int): ByteArray {
    require(width >= 0 && height >= 0) { "negative size" }
    require(pixels.size >= width * height) { "pixels too short: ${pixels.size} < ${width * height}" }
    val out = ByteArray(width * height * 3)
    var o = 0
    for (i in 0 until width * height) {
        val p = pixels[i]
        out[o++] = (p shr 16).toByte()
        out[o++] = (p shr 8).toByte()
        out[o++] = p.toByte()
    }
    return out
}
