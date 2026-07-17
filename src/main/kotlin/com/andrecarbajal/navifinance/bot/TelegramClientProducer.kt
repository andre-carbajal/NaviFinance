package com.andrecarbajal.navifinance.bot

import com.andrecarbajal.navifinance.config.BotConfig
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.generics.TelegramClient

@ApplicationScoped
class TelegramClientProducer(private val config: BotConfig) {
    @Produces
    @ApplicationScoped
    fun telegramClient(): TelegramClient = OkHttpTelegramClient(config.token())
}
