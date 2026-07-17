package com.andrecarbajal.navifinance.bot

import com.andrecarbajal.navifinance.bot.state.ConversationState
import com.andrecarbajal.navifinance.bot.state.ConversationStateManager
import com.andrecarbajal.navifinance.bot.state.ConversationStep
import com.andrecarbajal.navifinance.config.BotConfig
import com.andrecarbajal.navifinance.entity.Usuario
import com.andrecarbajal.navifinance.service.FinanceService
import com.andrecarbajal.navifinance.service.TransactionDraft
import com.andrecarbajal.navifinance.util.Money
import com.andrecarbajal.navifinance.vision.VoucherVisionClient
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

@ApplicationScoped
class TelegramBotService(
    private val config: BotConfig,
    private val finance: FinanceService,
    private val states: ConversationStateManager,
    private val vision: VoucherVisionClient,
    private val telegramClient: TelegramClient
) : LongPollingSingleThreadUpdateConsumer {
    private val log = Logger.getLogger(TelegramBotService::class.java)
    private var pollingApplication: TelegramBotsLongPollingApplication? = null

    fun register(@Observes event: StartupEvent) {
        if (config.token().isBlank()) {
            log.warn("Telegram bot is disabled: TELEGRAM_BOT_TOKEN is required")
            return
        }
        val application = TelegramBotsLongPollingApplication()
        try {
            application.registerBot(config.token(), this)
            pollingApplication = application
            log.info("Telegram bot registered with long polling")
        } catch (error: TelegramApiException) {
            runCatching { application.close() }
            throw IllegalStateException("Unable to register Telegram bot", error)
        }
    }

    fun shutdown(@Observes event: ShutdownEvent) {
        val application = pollingApplication ?: return
        pollingApplication = null
        runCatching { application.close() }
            .onSuccess { log.info("Telegram bot long polling stopped") }
            .onFailure { error -> log.error("Unable to stop Telegram bot long polling", error) }
    }

    override fun consume(update: Update) {
        runCatching {
            when {
                update.hasCallbackQuery() -> callback(update)
                update.hasMessage() && update.message.hasPhoto() -> photo(update)
                update.hasMessage() && update.message.hasText() -> text(update)
            }
        }.onFailure { error -> log.error("Failed to process Telegram update", error) }
    }

    private fun text(update: Update) {
        val message = update.message
        val chatId = message.chatId
        val user = finance.getOrCreateUser(message.from.id, message.from.userName ?: message.from.firstName)
        val input = message.text.trim()
        when (input.substringBefore(' ')) {
            "/start" -> start(chatId, user)
            "/cuenta_nueva" -> newAccount(chatId)
            "/cuentas" -> listAccounts(chatId, user)
            "/registrar" -> beginTransaction(chatId)
            "/resumen" -> summary(chatId, user)
            "/cancelar" -> {
                states.clear(chatId); send(chatId, "Flujo cancelado.")
            }

            else -> stateText(chatId, user, input)
        }
    }

    private fun start(chatId: Long, user: Usuario) {
        states.clear(chatId)
        if (finance.activeAccounts(user).isEmpty()) {
            send(chatId, "¡Bienvenido! Primero registremos tu primera cuenta.")
            newAccount(chatId)
        } else send(
            chatId,
            "¡Hola! Usa /registrar para añadir un retiro o abono. También: /cuentas, /resumen y /cancelar."
        )
    }

    private fun newAccount(chatId: Long) {
        states.put(chatId, ConversationState(ConversationStep.ACCOUNT_NAME))
        send(chatId, "¿Cómo se llama la cuenta? (por ejemplo: BCP principal)")
    }

    private fun listAccounts(chatId: Long, user: Usuario) {
        val accounts = finance.activeAccounts(user)
        send(
            chatId,
            if (accounts.isEmpty()) "No tienes cuentas activas. Usa /cuenta_nueva." else accounts.joinToString(
                "\n",
                "Tus cuentas:\n"
            ) { "• ${it.nombre} (${it.tipo})" })
    }

    private fun summary(chatId: Long, user: Usuario) {
        val totals = finance.monthlySummary(user)
        if (totals.isEmpty()) {
            send(chatId, "No registraste movimientos este mes.")
            return
        }
        val message = totals.joinToString("\n\n", "Resumen del mes:\n") { total ->
            "${if (total.currency == "USD") "🇺🇸 USD" else "🇵🇪 PEN"}\n" +
                    "Abonos: ${Money.format(total.income, total.currency)}\n" +
                    "Retiros: ${Money.format(total.expenses, total.currency)}\n" +
                    "Balance: ${Money.format(total.balance, total.currency)}"
        }
        send(chatId, message)
    }

    private fun beginTransaction(chatId: Long, draft: TransactionDraft = TransactionDraft(), user: Usuario? = null) {
        val state = ConversationState(ConversationStep.SELECTING_TYPE, draft)
        states.put(chatId, state)
        if (draft.type != null && user != null) chooseAccount(chatId, user, state)
        else send(
            chatId,
            "¿Qué deseas registrar?",
            keyboard(listOf(listOf(button("➖ Retiro", "type:retiro"), button("➕ Abono", "type:abono"))))
        )
    }

    private fun callback(update: Update) {
        val callback = update.callbackQuery
        val chatId = callback.message.chatId
        val user = finance.getOrCreateUser(callback.from.id, callback.from.userName ?: callback.from.firstName)
        val state = states.get(chatId) ?: run {
            send(
                chatId,
                "Este flujo venció. Usa /registrar para comenzar de nuevo."
            ); return
        }
        val data = callback.data
        when {
            data.startsWith("account-type:") && state.step == ConversationStep.ACCOUNT_TYPE -> {
                finance.createAccount(
                    user,
                    requireNotNull(state.accountName),
                    data.removePrefix("account-type:")
                ); states.clear(chatId)
                send(chatId, "Cuenta creada. Usa /registrar cuando quieras añadir un movimiento.")
            }

            data.startsWith("type:") && state.step == ConversationStep.SELECTING_TYPE -> {
                state.draft.type = data.removePrefix("type:")
                if (state.editingField == "type") finishEdit(chatId, user, state) else chooseAccount(
                    chatId,
                    user,
                    state
                )
            }

            data.startsWith("account:") && state.step == ConversationStep.SELECTING_ACCOUNT -> {
                finance.account(data.removePrefix("account:").toLongOrNull() ?: -1, user)?.let {
                    state.draft.accountId = it.id
                    if (state.editingField == "account") finishEdit(chatId, user, state)
                    else continueAfterAccount(chatId, user, state)
                }
                    ?: send(chatId, "Esa cuenta no está disponible. Elige otra.")
            }

            data.startsWith("currency:") && state.step == ConversationStep.SELECTING_CURRENCY -> {
                state.draft.currency = data.removePrefix("currency:")
                if (state.editingField == "currency") finishEdit(chatId, user, state) else chooseCategory(
                    chatId,
                    user,
                    state
                )
            }

            data.startsWith("category:") && state.step == ConversationStep.SELECTING_CATEGORY -> {
                val value = data.removePrefix("category:")
                if (value == "other") {
                    state.step = ConversationStep.EXPECTING_CATEGORY_NAME; send(
                        chatId,
                        "Escribe el nombre de la nueva categoría."
                    )
                } else finance.category(value.toLongOrNull() ?: -1, user)?.let {
                    state.draft.categoryId = it.id
                    if (state.editingField == "category") finishEdit(chatId, user, state) else continueAfterCategory(
                        chatId,
                        user,
                        state
                    )
                }
                    ?: send(chatId, "Esa categoría no está disponible. Elige otra.")
            }

            data == "edit" && state.step == ConversationStep.CONFIRMING -> showEditMenu(chatId, state)
            data.startsWith("edit-field:") && state.step == ConversationStep.SELECTING_EDIT_FIELD ->
                beginFieldEdit(chatId, user, state, data.removePrefix("edit-field:"))

            data == "description:skip" && state.step == ConversationStep.EXPECTING_DESCRIPTION -> {
                state.draft.description = null
                if (state.editingField == "description") finishEdit(chatId, user, state)
                else confirm(chatId, user, state)
            }

            data == "confirm" && state.step == ConversationStep.CONFIRMING -> {
                finance.saveTransaction(user, state.draft); states.clear(chatId); send(
                    chatId,
                    "✅ Transacción guardada."
                )
            }

            data == "cancel" -> {
                states.clear(chatId); send(chatId, "Flujo cancelado.")
            }

            data == "voucher:continue" && state.step == ConversationStep.VOUCHER_REVIEW -> beginTransaction(
                chatId,
                state.draft,
                user
            )

            data == "voucher:amount" && state.step == ConversationStep.VOUCHER_REVIEW -> {
                state.step = ConversationStep.EXPECTING_AMOUNT; send(chatId, "Escribe el monto correcto.")
            }

            else -> send(chatId, "Esa opción ya no es válida. Usa /registrar para comenzar de nuevo.")
        }
    }

    private fun stateText(chatId: Long, user: Usuario, input: String) {
        val state = states.get(chatId) ?: run { send(chatId, "No tengo un flujo activo. Usa /registrar."); return }
        when (state.step) {
            ConversationStep.ACCOUNT_NAME -> {
                state.accountName = input.take(80); state.step = ConversationStep.ACCOUNT_TYPE; send(
                    chatId,
                    "Selecciona el tipo.",
                    keyboard(
                        listOf(
                            listOf(
                                button("💳 Débito", "account-type:debito"),
                                button("💳 Crédito", "account-type:credito")
                            )
                        )
                    )
                )
            }

            ConversationStep.EXPECTING_AMOUNT -> Money.parse(input)?.let {
                state.draft.amount = it
                if (state.editingField == "amount") finishEdit(chatId, user, state)
                else {
                    state.step = ConversationStep.SELECTING_CURRENCY; askCurrency(chatId)
                }
            }
                ?: send(chatId, "Monto inválido. Ingresa un número positivo con hasta dos decimales.")

            ConversationStep.EXPECTING_CATEGORY_NAME -> {
                state.draft.categoryId = finance.categoryByNameOrCreate(user, input).id
                if (state.editingField == "category") finishEdit(chatId, user, state) else continueAfterCategory(
                    chatId,
                    user,
                    state
                )
            }

            ConversationStep.EXPECTING_DESCRIPTION -> {
                state.draft.description = if (input.equals("skip", true)) null else input.take(200)
                if (state.editingField == "description") finishEdit(chatId, user, state) else confirm(
                    chatId,
                    user,
                    state
                )
            }

            ConversationStep.EXPECTING_SOURCE -> {
                val source = input.trim().take(40)
                if (source.isBlank()) send(chatId, "El origen no puede quedar vacío.")
                else {
                    state.draft.source = source; finishEdit(chatId, user, state)
                }
            }

            ConversationStep.EXPECTING_DATE -> parseDate(input)?.let {
                state.draft.date = it
                finishEdit(chatId, user, state)
            } ?: send(chatId, "Fecha inválida. Usa el formato DD/MM/AAAA.")

            else -> send(chatId, "Usa los botones mostrados o /cancelar.")
        }
    }

    private fun chooseAccount(chatId: Long, user: Usuario, state: ConversationState) {
        val accounts = finance.activeAccounts(user)
        when (accounts.size) {
            0 -> {
                states.clear(chatId); send(chatId, "No tienes cuentas activas. Usa /cuenta_nueva primero.")
            }

            1 -> {
                state.draft.accountId = accounts.first().id
                continueAfterAccount(chatId, user, state)
            }

            else -> {
                state.step = ConversationStep.SELECTING_ACCOUNT; send(
                    chatId,
                    "Elige la cuenta.",
                    keyboard(accounts.map { listOf(button(it.nombre, "account:${it.id}")) })
                )
            }
        }
    }

    private fun continueAfterAccount(chatId: Long, user: Usuario, state: ConversationState) {
        if (nextStepAfterAccount(state.draft) == ConversationStep.EXPECTING_AMOUNT) {
            askAmount(chatId, state)
        } else {
            chooseCategory(chatId, user, state)
        }
    }

    private fun askAmount(chatId: Long, state: ConversationState) {
        state.step = ConversationStep.EXPECTING_AMOUNT; send(chatId, "¿Cuál es el monto?")
    }

    private fun askCurrency(chatId: Long) {
        send(
            chatId,
            "Moneda: 🇵🇪 Soles (S/) es la predeterminada.",
            keyboard(
                listOf(
                    listOf(button("🇵🇪 Soles (S/) — predeterminado", "currency:PEN")),
                    listOf(button("🇺🇸 Dólares (US$)", "currency:USD"))
                )
            )
        )
    }

    private fun chooseCategory(chatId: Long, user: Usuario, state: ConversationState) {
        state.step = ConversationStep.SELECTING_CATEGORY
        val buttons = finance.categories(user).map { listOf(button(it.nombre, "category:${it.id}")) } + listOf(
            listOf(
                button("Otra", "category:other")
            )
        )
        send(chatId, "Elige una categoría.", keyboard(buttons))
    }

    private fun askDescription(chatId: Long, state: ConversationState) {
        state.step = ConversationStep.EXPECTING_DESCRIPTION
        send(
            chatId,
            "Escribe una descripción corta o pulsa Omitir.",
            keyboard(listOf(listOf(button("⏭️ Omitir", "description:skip"))))
        )
    }

    private fun continueAfterCategory(chatId: Long, user: Usuario, state: ConversationState) {
        if (state.draft.description == null) askDescription(chatId, state) else confirm(chatId, user, state)
    }

    private fun confirm(chatId: Long, user: Usuario, state: ConversationState) {
        state.step = ConversationStep.CONFIRMING
        val account = state.draft.accountId?.let { finance.account(it, user)?.nombre } ?: "Sin cuenta"
        val category = state.draft.categoryId?.let { finance.category(it, user)?.nombre } ?: "Sin categoría"
        val description = state.draft.description ?: "Sin descripción"
        send(
            chatId,
            "Resumen:\nTipo: ${state.draft.type}\nCuenta: $account\nMonto: ${
                Money.format(
                    requireNotNull(state.draft.amount),
                    state.draft.currency
                )
            }\nCategoría: $category\nDescripción: $description\nOrigen: ${state.draft.source}\nFecha: ${
                formatDisplayDate(state.draft.date)
            }\n\n¿Confirmas?",
            keyboard(
                listOf(
                    listOf(button("✅ Confirmar", "confirm"), button("✏️ Editar", "edit")),
                    listOf(button("❌ Cancelar", "cancel"))
                )
            )
        )
    }

    private fun showEditMenu(chatId: Long, state: ConversationState) {
        state.step = ConversationStep.SELECTING_EDIT_FIELD
        send(
            chatId, "¿Qué dato deseas modificar?", keyboard(
                listOf(
                    listOf(button("Tipo", "edit-field:type"), button("Cuenta", "edit-field:account")),
                    listOf(button("Monto", "edit-field:amount"), button("Moneda", "edit-field:currency")),
                    listOf(button("Categoría", "edit-field:category"), button("Descripción", "edit-field:description")),
                    listOf(button("Origen", "edit-field:source"), button("Fecha", "edit-field:date")),
                    listOf(button("❌ Cancelar", "cancel"))
                )
            )
        )
    }

    private fun beginFieldEdit(chatId: Long, user: Usuario, state: ConversationState, field: String) {
        state.editingField = field
        when (field) {
            "type" -> {
                state.step = ConversationStep.SELECTING_TYPE; send(
                    chatId,
                    "Elige el nuevo tipo.",
                    keyboard(listOf(listOf(button("➖ Retiro", "type:retiro"), button("➕ Abono", "type:abono"))))
                )
            }

            "account" -> {
                val accounts = finance.activeAccounts(user)
                state.step = ConversationStep.SELECTING_ACCOUNT
                send(
                    chatId,
                    "Elige la nueva cuenta.",
                    keyboard(accounts.map { listOf(button(it.nombre, "account:${it.id}")) })
                )
            }

            "amount" -> {
                state.step = ConversationStep.EXPECTING_AMOUNT; send(chatId, "Escribe el nuevo monto.")
            }

            "currency" -> {
                state.step = ConversationStep.SELECTING_CURRENCY; askCurrency(chatId)
            }

            "category" -> chooseCategory(chatId, user, state)
            "description" -> {
                state.step = ConversationStep.EXPECTING_DESCRIPTION
                send(
                    chatId,
                    "Escribe la nueva descripción o pulsa Omitir para dejarla vacía.",
                    keyboard(listOf(listOf(button("⏭️ Omitir", "description:skip"))))
                )
            }

            "source" -> {
                state.step = ConversationStep.EXPECTING_SOURCE; send(chatId, "Escribe el banco o app de origen.")
            }

            "date" -> {
                state.step = ConversationStep.EXPECTING_DATE; send(
                    chatId,
                    "Escribe la nueva fecha en formato DD/MM/AAAA."
                )
            }

            else -> {
                state.editingField = null; confirm(chatId, user, state)
            }
        }
    }

    private fun finishEdit(chatId: Long, user: Usuario, state: ConversationState) {
        state.editingField = null
        confirm(chatId, user, state)
    }

    private fun parseDate(input: String): LocalDate? = runCatching {
        val parts = input.trim().split('/')
        require(parts.size == 3)
        LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
    }.getOrNull()

    private fun photo(update: Update) {
        val message = update.message
        val chatId = message.chatId
        finance.getOrCreateUser(message.from.id, message.from.userName ?: message.from.firstName)
        val photo = message.photo.maxByOrNull { it.fileSize ?: 0 } ?: return
        send(chatId, "🔎 Procesando voucher...")
        runCatching {
            val file = telegramClient.execute(GetFile(photo.fileId))
            val downloaded = telegramClient.downloadFile(file)
            try {
                val image = ImageIO.read(downloaded) ?: error("La imagen no es válida")
                vision.analyze(image)
            } finally {
                if (!downloaded.delete()) log.warn("Could not delete temporary voucher image")
            }
        }.onSuccess { detected ->
            val draft = TransactionDraft(
                type = detected.transactionType,
                amount = detected.amount,
                currency = detected.currency ?: "PEN",
                description = detected.description,
                source = detected.origin ?: "ollama",
                date = detected.date ?: LocalDate.now()
            )
            if (detected.amount == null) {
                states.put(chatId, ConversationState(ConversationStep.EXPECTING_AMOUNT, draft)); send(
                    chatId,
                    "No pude detectar el monto. Escríbelo manualmente."
                )
            } else {
                states.put(chatId, ConversationState(ConversationStep.VOUCHER_REVIEW, draft))
                val description = detected.description ?: "No detectada"
                val origin = detected.origin ?: "No detectado"
                send(
                    chatId,
                    "Datos detectados:\nTipo: ${detected.transactionType ?: "No detectado"}\nMonto: ${
                        Money.format(
                            detected.amount,
                            draft.currency
                        )
                    }\nFecha: ${formatDisplayDate(draft.date)}\nDescripción: $description\nOrigen: $origin\n\nRevísalos antes de continuar.",
                    keyboard(
                        listOf(
                            listOf(
                                button("✅ Continuar", "voucher:continue"),
                                button("✏️ Editar monto", "voucher:amount")
                            ), listOf(button("❌ Cancelar", "cancel"))
                        )
                    )
                )
            }
        }.onFailure { error ->
            log.warn("Voucher vision analysis failed", error)
            send(chatId, "No pude analizar la imagen. Registra el movimiento manualmente con /registrar.")
        }
    }

    private fun send(chatId: Long, text: String, markup: InlineKeyboardMarkup? = null) {
        runCatching { telegramClient.execute(SendMessage(chatId.toString(), text).also { it.replyMarkup = markup }) }
            .onFailure { error -> log.error("Failed to send Telegram message", error) }
    }

    private fun button(text: String, data: String): InlineKeyboardButton =
        InlineKeyboardButton(text).also { it.callbackData = data }

    private fun keyboard(rows: List<List<InlineKeyboardButton>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(rows.map(::InlineKeyboardRow))
}

internal fun nextStepAfterAccount(draft: TransactionDraft): ConversationStep =
    if (draft.amount == null) ConversationStep.EXPECTING_AMOUNT else ConversationStep.SELECTING_CATEGORY

private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

internal fun formatDisplayDate(date: LocalDate): String = date.format(DISPLAY_DATE_FORMATTER)
