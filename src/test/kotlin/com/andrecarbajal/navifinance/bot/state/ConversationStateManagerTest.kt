package com.andrecarbajal.navifinance.bot.state

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ConversationStateManagerTest {
    @Test fun `keeps a fresh state and clears it explicitly`() {
        val manager = ConversationStateManager()
        manager.put(10, ConversationState(ConversationStep.SELECTING_TYPE))
        assertEquals(ConversationStep.SELECTING_TYPE, manager.get(10)?.step)
        manager.clear(10)
        assertNull(manager.get(10))
    }

    @Test fun `expires a state after ten minutes`() {
        val manager = ConversationStateManager()
        manager.put(10, ConversationState(ConversationStep.SELECTING_TYPE, lastTouched = Instant.now().minusSeconds(601)))

        assertNull(manager.get(10))
        assertNull(manager.get(10))
    }
}
