package com.andrecarbajal.navifinance.vision

import com.andrecarbajal.navifinance.util.Money
import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.config.ConfigMapping
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*
import javax.imageio.ImageIO

data class VoucherData(
    val transactionType: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val date: LocalDate?,
    val description: String?,
    val origin: String?
)

data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val images: List<String>,
    val stream: Boolean = false,
    val format: Map<String, Any>,
    val options: Map<String, Any> = mapOf("temperature" to 0)
)

data class OllamaGenerateResponse(val response: String = "", val done: Boolean = false)

private data class VoucherModelResponse(
    val tipo: String? = null,
    val monto: String? = null,
    val moneda: String? = null,
    val fecha: String? = null,
    val descripcion: String? = null,
    val origen: String? = null
)

@Path("/api/generate")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "ollama-api")
interface OllamaApi {
    @POST
    fun generate(request: OllamaGenerateRequest): OllamaGenerateResponse
}

@ConfigMapping(prefix = "ollama")
interface OllamaConfig {
    fun model(): String
}

@ApplicationScoped
class VoucherVisionClient(
    @RestClient private val api: OllamaApi,
    private val config: OllamaConfig,
    private val objectMapper: ObjectMapper
) {
    fun analyze(image: BufferedImage): VoucherData {
        val request = OllamaGenerateRequest(
            model = config.model(),
            prompt = PROMPT,
            images = listOf(ImageEncoder.toBase64Jpeg(image)),
            format = RESPONSE_SCHEMA
        )
        val generated = api.generate(request)
        require(generated.done) { "Ollama did not complete voucher analysis" }
        val parsed = objectMapper.readValue(generated.response, VoucherModelResponse::class.java)
        val currency = parsed.moneda?.uppercase()?.takeIf { it in SUPPORTED_CURRENCIES }
        return VoucherData(
            transactionType = parsed.tipo?.lowercase()?.takeIf { it in TRANSACTION_TYPES },
            amount = parsed.monto?.let(Money::parse),
            currency = currency,
            date = parsed.fecha?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            description = parsed.descripcion.clean(200),
            origin = parsed.origen.clean(40)
        )
    }

    private fun String?.clean(maxLength: Int): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)

    companion object {
        private val SUPPORTED_CURRENCIES = setOf("PEN", "USD")
        private val TRANSACTION_TYPES = setOf("retiro", "abono")
        private val RESPONSE_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "tipo" to mapOf("type" to listOf("string", "null"), "enum" to listOf("retiro", "abono", null)),
                "monto" to mapOf("type" to listOf("number", "null")),
                "moneda" to mapOf("type" to listOf("string", "null"), "enum" to listOf("PEN", "USD", null)),
                "fecha" to mapOf("type" to listOf("string", "null")),
                "descripcion" to mapOf("type" to listOf("string", "null")),
                "origen" to mapOf("type" to listOf("string", "null"))
            ),
            "required" to listOf("tipo", "monto", "moneda", "fecha", "descripcion", "origen"),
            "additionalProperties" to false
        )
        private val PROMPT = """
            Analiza esta imagen de un voucher o comprobante de pago bancario o de una app de pagos.
            Extrae únicamente el tipo de transacción, monto principal, moneda, fecha, destinatario/comercio/concepto,
            banco o app de origen. No confundas el monto con comisiones, números de operación, CCI,
            teléfonos u otros números. El monto principal suele ser el número más grande y destacado visualmente.
            Usa tipo "retiro" cuando el comprobante indique que el usuario envió, pagó o transfirió dinero, y "abono"
            cuando indique que recibió dinero, le pagaron o recibió una transferencia. Usa fecha YYYY-MM-DD, moneda
            PEN o USD y null para cualquier campo ilegible o cuando no se pueda determinar la dirección.
        """.trimIndent()
    }
}

object ImageEncoder {
    fun resize(source: BufferedImage, maxDimension: Int = 1600): BufferedImage {
        require(maxDimension > 0)
        val largest = maxOf(source.width, source.height)
        if (largest <= maxDimension) return source
        val scale = maxDimension.toDouble() / largest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = resized.createGraphics()
        try {
            graphics.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return resized
    }

    fun toBase64Jpeg(source: BufferedImage): String {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(resize(source), "jpg", output)) { "JPEG encoder is unavailable" }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
