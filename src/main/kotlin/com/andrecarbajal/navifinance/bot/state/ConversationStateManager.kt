package com.andrecarbajal.navifinance.bot.state

import com.andrecarbajal.navifinance.service.TransactionDraft
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class ConversationStep {
    ACCOUNT_NAME, ACCOUNT_TYPE, SELECTING_TYPE, SELECTING_ACCOUNT, EXPECTING_AMOUNT,
    SELECTING_CURRENCY, SELECTING_CATEGORY, EXPECTING_CATEGORY_NAME, EXPECTING_DESCRIPTION,
    EXPECTING_DATE, SELECTING_EDIT_FIELD, CONFIRMING, OCR_REVIEW
}

data class ConversationState(
    var step: ConversationStep,
    val draft: TransactionDraft = TransactionDraft(),
    var accountName: String? = null,
    var editingField: String? = null,
    var lastTouched: Instant = Instant.now()
)

@ApplicationScoped
class ConversationStateManager {
    private val states = ConcurrentHashMap<Long, ConversationState>()
    private val timeout = Duration.ofMinutes(10)

    fun put(chatId: Long, state: ConversationState) { states[chatId] = state }
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
    fun clear(chatId: Long) { states.remove(chatId) }
}
