package com.andrecarbajal.navifinance.vision

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.math.BigDecimal
import java.time.LocalDate

class VoucherVisionClientTest {
    @Test
    fun `sends structured non-streaming request and maps valid response`() {
        val api = FakeOllamaApi("""{"tipo":"retiro","monto":125.50,"moneda":"PEN","fecha":"2026-07-14","descripcion":"Mercado","origen":"Yape"}""")
        val result = client(api).analyze(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB))

        assertEquals("retiro", result.transactionType)
        assertEquals(BigDecimal("125.50"), result.amount)
        assertEquals("PEN", result.currency)
        assertEquals(LocalDate.of(2026, 7, 14), result.date)
        assertEquals("Mercado", result.description)
        assertEquals("Yape", result.origin)
        assertEquals("qwen3-vl:2b-instruct", api.request.model)
        assertFalse(api.request.stream)
        assertTrue(api.request.images.single().isNotBlank())
        assertEquals("object", api.request.format["type"])
        val properties = api.request.format["properties"] as Map<*, *>
        assertFalse(properties.containsKey("confianza"))
    }

    @Test
    fun `invalid model fields are safely discarded`() {
        val api = FakeOllamaApi("""{"tipo":"desconocido","monto":"-10","moneda":"EUR","fecha":"ayer","descripcion":" ","origen":null}""")
        val result = client(api).analyze(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB))

        assertNull(result.transactionType)
        assertNull(result.amount)
        assertNull(result.currency)
        assertNull(result.date)
        assertNull(result.description)
        assertNull(result.origin)
    }

    private fun client(api: OllamaApi) = VoucherVisionClient(
        api,
        object : OllamaConfig { override fun model() = "qwen3-vl:2b-instruct" },
        ObjectMapper().findAndRegisterModules()
    )

    private class FakeOllamaApi(private val body: String) : OllamaApi {
        lateinit var request: OllamaGenerateRequest
        override fun generate(request: OllamaGenerateRequest): OllamaGenerateResponse {
            this.request = request
            return OllamaGenerateResponse(body, true)
        }
    }
}
