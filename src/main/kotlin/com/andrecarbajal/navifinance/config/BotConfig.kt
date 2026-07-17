package com.andrecarbajal.navifinance.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "telegram.bot")
interface BotConfig {
    fun token(): String
}
