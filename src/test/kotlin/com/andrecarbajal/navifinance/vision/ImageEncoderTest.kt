package com.andrecarbajal.navifinance.vision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class ImageEncoderTest {
    @Test
    fun `small image is not enlarged`() {
        val image = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        assertSame(image, ImageEncoder.resize(image))
    }

    @Test
    fun `large image is resized preserving aspect ratio`() {
        val result = ImageEncoder.resize(BufferedImage(3200, 2400, BufferedImage.TYPE_INT_RGB))
        assertEquals(1600, result.width)
        assertEquals(1200, result.height)
    }
}
