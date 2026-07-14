package com.andrecarbajal.navifinance.util

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    fun parse(input: String): BigDecimal? = try {
        val normalized = input.trim().replace(" ", "").replace(',', '.')
        if (!Regex("^\\d+(?:\\.\\d{1,2})?$").matches(normalized)) null
        else BigDecimal(normalized).setScale(2, RoundingMode.UNNECESSARY).takeIf { it > BigDecimal.ZERO }
    } catch (_: ArithmeticException) { null }

    fun format(amount: BigDecimal, currency: String): String = when (currency) {
        "USD" -> "US$ ${amount.setScale(2)}"
        else -> "S/ ${amount.setScale(2)}"
    }
}
