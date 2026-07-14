package com.andrecarbajal.navifinance.ocr

import com.andrecarbajal.navifinance.config.OcrConfig
import com.andrecarbajal.navifinance.util.Money
import jakarta.enterprise.context.ApplicationScoped
import net.sourceforge.tess4j.ITessAPI.TessPageSegMode
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.time.LocalDate

data class VoucherData(val amount: BigDecimal?, val currency: String, val date: LocalDate?, val rawText: String)

@ApplicationScoped
class VoucherParser(private val ocrConfig: OcrConfig) {
    fun parse(image: BufferedImage, pageSegMode: Int = TessPageSegMode.PSM_AUTO): VoucherData {
        val text = Tesseract().apply {
            setDatapath(ocrConfig.tessdataPath())
            setLanguage("spa")
            setPageSegMode(pageSegMode)
        }.doOCR(image)
        return VoucherTextExtractor.extract(text)
    }
}

object VoucherTextExtractor {
    private val currencyAmountRegex = Regex(
        "(?<![\\p{L}\\p{N}])((?:[S5§]\\s*[/.]|US\\s*\\$|USD))\\s*([0-9Oo]{1,9}(?:[.,][0-9Oo]{1,2})?)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE
    )
    private val decimalAmountRegex = Regex("\\b(\\d{1,9}[.,]\\d{2})\\b")
    private val dateRegex = Regex("\\b(\\d{1,2}/\\d{1,2}/\\d{2,4})\\b")

    fun extract(text: String): VoucherData {
        val markedAmount = currencyAmountRegex.find(text)
        val amount = markedAmount?.groupValues?.get(2)?.let(::normalizeOcrDigits)?.let(Money::parse)
            ?: decimalAmountRegex.find(text)?.groupValues?.get(1)?.let(Money::parse)
        val currency = markedAmount?.groupValues?.get(1)?.let(::currencyFromMarker)
            ?: if (Regex("(?:US\\s*\\$|USD)", RegexOption.IGNORE_CASE).containsMatchIn(text)) "USD" else "PEN"
        return VoucherData(amount, currency, dateRegex.find(text)?.groupValues?.get(1)?.let(::parseDate), text)
    }

    private fun currencyFromMarker(marker: String): String =
        if (Regex("(?:US\\s*\\$|USD)", RegexOption.IGNORE_CASE).containsMatchIn(marker)) "USD" else "PEN"

    private fun normalizeOcrDigits(value: String): String = value.replace('O', '0').replace('o', '0')

    private fun parseDate(value: String): LocalDate? = runCatching {
        val parts = value.split('/')
        val year = parts[2].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
        LocalDate.of(year, parts[1].toInt(), parts[0].toInt())
    }.getOrNull()
}
