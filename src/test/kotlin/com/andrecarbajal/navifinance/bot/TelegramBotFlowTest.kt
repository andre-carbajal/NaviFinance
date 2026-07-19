package com.andrecarbajal.navifinance.bot

import com.andrecarbajal.navifinance.bot.state.ConversationStep
import com.andrecarbajal.navifinance.service.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class TelegramBotFlowTest {
    @Test
    fun `command menu exposes every supported bot command`() {
        val commands = botCommands()

        assertEquals(
            listOf("start", "cuentas", "registrar", "resumen", "cancelar"),
            commands.map { it.command }
        )
        assertEquals(false, commands.any { it.command == "cuenta_nueva" })
        assertEquals(true, commands.all { it.description.isNotBlank() })
    }

    @Test
    fun `display date uses day month year format`() {
        assertEquals("14/07/2026", formatDisplayDate(LocalDate.of(2026, 7, 14)))
    }

    @Test
    fun `summary detail callback carries account and month without conversation state`() {
        assertEquals(
            SummaryDetailRequest(42, YearMonth.of(2026, 7)),
            parseSummaryDetailCallback("summary-detail:42:2026-07")
        )
        assertEquals(null, parseSummaryDetailCallback("summary-detail:other"))
    }

    @Test
    fun `account summary labels overdraft and credit balance in favor`() {
        val month = YearMonth.of(2026, 7)
        val debit = formatAccountSummary(
            AccountSummary(
                1,
                "BCP",
                "debito",
                month,
                listOf(AccountCurrencySummary("PEN", BigDecimal.ZERO, BigDecimal.TEN, BigDecimal("-10.00")))
            )
        )
        val credit = formatAccountSummary(
            AccountSummary(
                2,
                "Visa",
                "credito",
                month,
                listOf(AccountCurrencySummary("USD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal("-10.00")))
            )
        )

        assertEquals(true, debit.contains("Sobregiro: S/ 10.00"))
        assertEquals(true, credit.contains("Saldo a favor: US$ 10.00"))
    }

    @Test
    fun `category detail only renders currencies with movements`() {
        val text = formatCategorySummary(
            AccountCategorySummary(
                1,
                "BCP",
                YearMonth.of(2026, 7),
                listOf(CategoryCurrencySummary("Comida", "PEN", BigDecimal.ZERO, BigDecimal("20.00")))
            )
        )

        assertEquals(true, text.contains("Comida"))
        assertEquals(true, text.contains("Retiros: S/ 20.00"))
        assertEquals(false, text.contains("🇺🇸 USD"))
    }

    @Test
    fun `debit category detail identifies linked card payments`() {
        val text = formatCategorySummary(
            AccountCategorySummary(
                1,
                "BCP",
                YearMonth.of(2026, 7),
                listOf(CategoryCurrencySummary("Otros", "PEN", BigDecimal.ZERO, BigDecimal("100.00"))),
                debitPayments = listOf(DebitPaymentDetail("Otros", BigDecimal("100.00"), "PEN", "Visa"))
            )
        )

        assertEquals(true, text.contains("Pagos a tarjeta"))
        assertEquals(true, text.contains("Visa"))
    }

    @Test
    fun `manual transaction asks for amount after account`() {
        assertEquals(
            ConversationStep.EXPECTING_AMOUNT,
            nextStepAfterAccount(TransactionDraft(source = "manual"))
        )
    }

    @Test
    fun `transaction requires registration when user has no active accounts`() {
        assertEquals(true, accountRegistrationRequired(0))
        assertEquals(false, accountRegistrationRequired(1))
    }

    @Test
    fun `vision transaction with detected amount advances to category`() {
        val date = LocalDate.of(2026, 7, 14)
        val draft = TransactionDraft(
            amount = BigDecimal("100.00"),
            currency = "PEN",
            description = "Mercado",
            source = "Yape",
            date = date
        )

        assertEquals(ConversationStep.SELECTING_CATEGORY, nextStepAfterAccount(draft))
        assertEquals(BigDecimal("100.00"), draft.amount)
        assertEquals("PEN", draft.currency)
        assertEquals("Mercado", draft.description)
        assertEquals("Yape", draft.source)
        assertEquals(date, draft.date)
    }
}
