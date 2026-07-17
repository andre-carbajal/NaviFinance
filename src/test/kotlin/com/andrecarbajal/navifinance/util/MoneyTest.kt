package com.andrecarbajal.navifinance.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `accepts comma and dot decimals`() {
        assertEquals(BigDecimal("12.50"), Money.parse("12,50"))
        assertEquals(BigDecimal("12.50"), Money.parse("12.50"))
    }

    @Test
    fun `rejects invalid values`() {
        listOf("0", "-4", "abc", "1.234", "10000000000").forEach { assertNull(Money.parse(it)) }
    }

    @Test
    fun `accepts zero only for account balances and enforces database precision`() {
        assertEquals(BigDecimal("0.00"), Money.parseNonNegative("0"))
        assertEquals(BigDecimal("9999999999.99"), Money.parseNonNegative("9999999999.99"))
        listOf("-0.01", "1.234", "10000000000").forEach { assertNull(Money.parseNonNegative(it)) }
    }

    @Test
    fun `parses PEN per USD exchange rates with up to six decimals`() {
        assertEquals(BigDecimal("3.750000"), Money.parseExchangeRate("3,75"))
        listOf("0", "-3.75", "3.1234567", "abc").forEach { assertNull(Money.parseExchangeRate(it)) }
    }

    @Test
    fun `formats supported currencies`() {
        assertEquals("S/ 10.00", Money.format(BigDecimal.TEN, "PEN"))
        assertEquals("US$ 10.00", Money.format(BigDecimal.TEN, "USD"))
    }
}
