package com.andrecarbajal.navifinance.bot.state

import com.andrecarbajal.navifinance.service.TransactionDraft
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ConversationStep {
    ACCOUNT_NAME, ACCOUNT_TYPE, ACCOUNT_BALANCE_PEN, ACCOUNT_BALANCE_USD, ACCOUNT_CONFIRMING,
    SELECTING_TYPE, SELECTING_ACCOUNT, EXPECTING_AMOUNT,
    SELECTING_CURRENCY, SELECTING_CATEGORY, EXPECTING_CATEGORY_NAME, EXPECTING_DESCRIPTION, EXPECTING_SOURCE,
    EXPECTING_DATE, SELECTING_EDIT_FIELD, CONFIRMING, VOUCHER_REVIEW
}

enum class ResumeAction { SUMMARY, REGISTER, VOUCHER_RETRY }

data class ConversationState(
    var step: ConversationStep,
    val draft: TransactionDraft = TransactionDraft(),
    var accountName: String? = null,
    var accountType: String? = null,
    var accountBalancePen: BigDecimal? = null,
    var accountBalanceUsd: BigDecimal? = null,
    var accountIdToConfigure: Long? = null,
    var resumeAction: ResumeAction? = null,
    var editingField: String? = null,
    var lastTouched: Instant = Instant.now()
)

@ApplicationScoped
class ConversationStateManager {
    private val states = ConcurrentHashMap<Long, ConversationState>()
    private val timeout = Duration.ofMinutes(10)

    fun put(chatId: Long, state: ConversationState) {
        states[chatId] = state
    }

    fun get(chatId: Long): ConversationState? {
        val state = states[chatId] ?: return null
        val now = Instant.now()
        if (!now.isBefore(state.lastTouched.plus(timeout))) {
            states.remove(chatId, state)
            return null
        }
        state.lastTouched = now
        return state
    }

    fun clear(chatId: Long) {
        states.remove(chatId)
    }
}
