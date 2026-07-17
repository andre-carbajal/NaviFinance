package com.andrecarbajal.navifinance.bot

import com.andrecarbajal.navifinance.bot.state.ConversationStep
import com.andrecarbajal.navifinance.service.TransactionDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class TelegramBotFlowTest {
    @Test
    fun `display date uses day month year format`() {
        assertEquals("14/07/2026", formatDisplayDate(LocalDate.of(2026, 7, 14)))
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
