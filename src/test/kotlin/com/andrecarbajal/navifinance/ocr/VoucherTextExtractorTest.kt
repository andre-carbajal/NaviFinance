package com.andrecarbajal.navifinance.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class VoucherTextExtractorTest {
    @Test fun `extracts Yape integer amount in soles`() {
        val voucher = VoucherTextExtractor.extract("¡Te Yapearon!\ns/215\n13 jul. 2026")

        assertEquals(BigDecimal("215.00"), voucher.amount)
        assertEquals("PEN", voucher.currency)
    }

    @Test fun `extracts marked soles and dollars with decimals`() {
        assertEquals(BigDecimal("215.50"), VoucherTextExtractor.extract("S/ 215,50").amount)
        val dollars = VoucherTextExtractor.extract("USD 12.99")
        assertEquals(BigDecimal("12.99"), dollars.amount)
        assertEquals("USD", dollars.currency)
    }

    @Test fun `normalizes common Yape currency marker OCR errors`() {
        assertEquals(BigDecimal("100.00"), VoucherTextExtractor.extract("5/100").amount)
        assertEquals(BigDecimal("100.00"), VoucherTextExtractor.extract("§/100").amount)
        assertEquals("PEN", VoucherTextExtractor.extract("5/100").currency)
    }

    @Test fun `normalizes letters confused with zero in a marked amount`() {
        val voucher = VoucherTextExtractor.extract("S/1OO")

        assertEquals(BigDecimal("100.00"), voucher.amount)
        assertEquals("PEN", voucher.currency)
    }

    @Test fun `keeps accepting valid dollar marker formats`() {
        assertEquals(BigDecimal("100.00"), VoucherTextExtractor.extract("US$100").amount)
        assertEquals("USD", VoucherTextExtractor.extract("US$100").currency)
    }

    @Test fun `does not treat an unmarked operation number as an amount`() {
        assertNull(VoucherTextExtractor.extract("Nro. de operación 27533746").amount)
    }
}
