package com.andrecarbajal.navifinance.util

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    private val MAX_AMOUNT = BigDecimal("9999999999.99")
    private val MAX_EXCHANGE_RATE = BigDecimal("999999.999999")

    fun parse(input: String): BigDecimal? = parseAmount(input, allowZero = false)

    fun parseNonNegative(input: String): BigDecimal? = parseAmount(input, allowZero = true)

    fun parseExchangeRate(input: String): BigDecimal? = try {
        val normalized = input.trim().replace(" ", "").replace(',', '.')
        if (!Regex("^\\d+(?:\\.\\d{1,6})?$").matches(normalized)) null
        else BigDecimal(normalized).setScale(6, RoundingMode.UNNECESSARY)
            .takeIf { it > BigDecimal.ZERO && it <= MAX_EXCHANGE_RATE }
    } catch (_: ArithmeticException) {
        null
    }

    private fun parseAmount(input: String, allowZero: Boolean): BigDecimal? = try {
        val normalized = input.trim().replace(" ", "").replace(',', '.')
        if (!Regex("^\\d+(?:\\.\\d{1,2})?$").matches(normalized)) null
        else BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY).takeIf {
            it <= MAX_AMOUNT && (it.signum() > 0 || allowZero && it.signum() == 0)
        }
    } catch (_: ArithmeticException) {
        null
    }

    fun format(amount: BigDecimal, currency: String): String = when (currency) {
        "USD" -> "US$ ${amount.setScale(2)}"
        else -> "S/ ${amount.setScale(2)}"
    }
}
