package com.andrecarbajal.navifinance.ocr

import jakarta.enterprise.context.ApplicationScoped
import java.awt.Color
import java.awt.image.BufferedImage

@ApplicationScoped
class ImagePreprocessor {
    fun threshold(source: BufferedImage, threshold: Int = 170): BufferedImage {
        val result = BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_BINARY)
        for (x in 0 until source.width) for (y in 0 until source.height) {
            val color = Color(source.getRGB(x, y))
            val luminance = (color.red * 0.299 + color.green * 0.587 + color.blue * 0.114).toInt()
            result.setRGB(x, y, if (luminance >= threshold) Color.WHITE.rgb else Color.BLACK.rgb)
        }
        return result
    }
}
