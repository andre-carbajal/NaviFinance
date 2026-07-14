package com.andrecarbajal.navifinance.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "ocr")
interface OcrConfig {
    fun tessdataPath(): String
}
