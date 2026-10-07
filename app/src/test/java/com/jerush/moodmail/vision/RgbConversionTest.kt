package com.jerush.moodmail.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RgbConversionTest {

    // 2x2, row-major: red, green / blue, mixed. Alpha varies to prove it is dropped.
    private val pixels = intArrayOf(
        0xFFFF0000.toInt(), // A=FF R=FF G=00 B=00
        0x8000FF00.toInt(), // A=80 R=00 G=FF B=00
        0x000000FF,         // A=00 R=00 G=00 B=FF
        0x7F102030,         // A=7F R=10 G=20 B=30
    )

    @Test
    fun lengthIsWidthTimesHeightTimesThree() {
        assertEquals(12, bitmapPixelsToRgb(pixels, 2, 2).size)
    }

    @Test
    fun bytesAreRgbOrderRowMajorWithAlphaDropped() {
        val expected = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00,
            0x00, 0xFF.toByte(), 0x00,
            0x00, 0x00, 0xFF.toByte(),
            0x10, 0x20, 0x30,
        )
        assertArrayEquals(expected, bitmapPixelsToRgb(pixels, 2, 2))
    }

    @Test
    fun alphaValueDoesNotChangeOutput() {
        val opaque = intArrayOf(0xFF112233.toInt())
        val transparent = intArrayOf(0x00112233)
        assertArrayEquals(bitmapPixelsToRgb(opaque, 1, 1), bitmapPixelsToRgb(transparent, 1, 1))
    }
}
