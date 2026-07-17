package com.andrecarbajal.navifinance.service

import com.andrecarbajal.navifinance.entity.Categoria
import com.andrecarbajal.navifinance.entity.Cuenta
import com.andrecarbajal.navifinance.entity.Transaccion
import com.andrecarbajal.navifinance.entity.Usuario
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class FinanceSummaryTest {
    @Test
    fun `debit and credit accounts apply opposite balance formulas`() {
        assertEquals(
            BigDecimal("850.00"),
            calculateCurrentBalance("debito", bd("1000"), bd("50"), bd("200"))
        )
        assertEquals(
            BigDecimal("1150.00"),
            calculateCurrentBalance("credito", bd("1000"), bd("50"), bd("200"))
        )
    }

    @Test
    fun `summary separates currencies and only post snapshot creations affect current balance`() {
        val snapshot = LocalDateTime.of(2026, 7, 16, 10, 0)
        val account = account(1, "BCP", "debito", "1000", "50", snapshot)
        val oldWithdrawal = transaction(account, "Comida", "retiro", "100", "PEN", snapshot.minusDays(1))
        val backdatedNewDeposit = transaction(account, "Otros", "abono", "25", "PEN", snapshot.plusHours(1))
            .also { it.fecha = LocalDate.of(2026, 7, 1) }
        val usdWithdrawal = transaction(account, "Ocio", "retiro", "10", "USD", snapshot.plusHours(2))

        val result = summarizeAccounts(
            listOf(account),
            listOf(oldWithdrawal, backdatedNewDeposit, usdWithdrawal),
            listOf(oldWithdrawal, backdatedNewDeposit, usdWithdrawal),
            YearMonth.of(2026, 7)
        ).single()

        assertEquals(bd("25"), result.currencies.single { it.currency == "PEN" }.income)
        assertEquals(bd("100"), result.currencies.single { it.currency == "PEN" }.expenses)
        assertEquals(bd("1025"), result.currencies.single { it.currency == "PEN" }.currentBalance)
        assertEquals(bd("40"), result.currencies.single { it.currency == "USD" }.currentBalance)
    }

    @Test
    fun `category detail groups income and expenses by category and currency`() {
        val account = account(1, "Visa", "credito", "0", "0", LocalDateTime.of(2026, 7, 1, 0, 0))
        val rows = listOf(
            transaction(account, "Comida", "retiro", "20", "PEN"),
            transaction(account, "Comida", "abono", "5", "PEN"),
            transaction(account, "Comida", "retiro", "8", "USD")
        )

        val entries = summarizeCategories(account, rows, YearMonth.of(2026, 7)).entries

        assertEquals(2, entries.size)
        assertEquals(bd("5"), entries.single { it.currency == "PEN" }.income)
        assertEquals(bd("20"), entries.single { it.currency == "PEN" }.expenses)
        assertEquals(bd("8"), entries.single { it.currency == "USD" }.expenses)
    }

    private fun account(
        id: Long,
        name: String,
        type: String,
        pen: String,
        usd: String,
        snapshot: LocalDateTime
    ) = Cuenta().also {
        it.id = id
        it.usuario = Usuario()
        it.nombre = name
        it.tipo = type
        it.saldoBasePen = bd(pen)
        it.saldoBaseUsd = bd(usd)
        it.saldoConfiguradoEn = snapshot
    }

    private fun transaction(
        account: Cuenta,
        categoryName: String,
        type: String,
        amount: String,
        currency: String,
        createdAt: LocalDateTime = LocalDateTime.of(2026, 7, 16, 12, 0)
    ) = Transaccion().also {
        it.usuario = account.usuario
        it.cuenta = account
        it.categoria = Categoria().also { category ->
            category.usuario = account.usuario
            category.nombre = categoryName
        }
        it.tipo = type
        it.monto = bd(amount)
        it.moneda = currency
        it.origen = "manual"
        it.fecha = LocalDate.of(2026, 7, 16)
        it.creadoEn = createdAt
    }

    private fun bd(value: String): BigDecimal = BigDecimal(value).setScale(2)
}
