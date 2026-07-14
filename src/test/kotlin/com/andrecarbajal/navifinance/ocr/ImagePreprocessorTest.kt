package com.andrecarbajal.navifinance.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ImagePreprocessorTest {
    @Test fun `creates binary image`() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, Color.BLACK.rgb); it.setRGB(1, 0, Color.WHITE.rgb) }
        val result = ImagePreprocessor().threshold(image)
        assertEquals(Color.BLACK.rgb, result.getRGB(0, 0))
        assertEquals(Color.WHITE.rgb, result.getRGB(1, 0))
    }
}
