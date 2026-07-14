package com.andrecarbajal.navifinance.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test fun `accepts comma and dot decimals`() {
        assertEquals(BigDecimal("12.50"), Money.parse("12,50"))
        assertEquals(BigDecimal("12.50"), Money.parse("12.50"))
    }

    @Test fun `rejects invalid values`() {
        listOf("0", "-4", "abc", "1.234").forEach { assertNull(Money.parse(it)) }
    }

    @Test fun `formats supported currencies`() {
        assertEquals("S/ 10.00", Money.format(BigDecimal.TEN, "PEN"))
        assertEquals("US$ 10.00", Money.format(BigDecimal.TEN, "USD"))
    }
}
