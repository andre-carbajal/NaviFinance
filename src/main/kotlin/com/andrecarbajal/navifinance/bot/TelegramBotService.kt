package com.andrecarbajal.navifinance.bot

import com.andrecarbajal.navifinance.bot.state.ConversationState
import com.andrecarbajal.navifinance.bot.state.ConversationStateManager
import com.andrecarbajal.navifinance.bot.state.ConversationStep
import com.andrecarbajal.navifinance.bot.state.ResumeAction
import com.andrecarbajal.navifinance.config.BotConfig
import com.andrecarbajal.navifinance.entity.Usuario
import com.andrecarbajal.navifinance.service.*
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
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
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
            check(telegramClient.execute(SetMyCommands(botCommands()))) {
                "Telegram rejected the bot command menu"
            }
            pollingApplication = application
            log.info("Telegram bot registered with long polling and command menu")
        } catch (error: Exception) {
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
            "/cuentas" -> listAccounts(chatId, user)
            "/registrar" -> if (
                ensureAccountRegistered(chatId, user) &&
                ensureBalancesConfigured(chatId, user, ResumeAction.REGISTER)
            ) beginTransaction(chatId)

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
            send(chatId, "¡Bienvenido! Primero registremos tu primera cuenta desde el menú de cuentas.")
            listAccounts(chatId, user)
        } else send(
            chatId,
            "¡Hola! Usa /registrar para añadir movimientos o pagar una tarjeta. También: /cuentas, /resumen y /cancelar."
        )
    }

    private fun newAccount(chatId: Long) {
        states.put(chatId, ConversationState(ConversationStep.ACCOUNT_NAME))
        send(chatId, "¿Cómo se llama la cuenta? (por ejemplo: BCP principal)")
    }

    private fun ensureAccountRegistered(chatId: Long, user: Usuario): Boolean {
        if (!accountRegistrationRequired(finance.activeAccounts(user).size)) return true

        states.clear(chatId)
        send(chatId, "Primero debes registrar una cuenta para poder añadir movimientos.")
        newAccount(chatId)
        return false
    }

    private fun ensureBalancesConfigured(chatId: Long, user: Usuario, resumeAction: ResumeAction): Boolean {
        val pending = finance.pendingBalanceAccounts(user).firstOrNull() ?: return true
        beginBalanceConfiguration(
            chatId,
            pending.id ?: return false,
            pending.nombre,
            pending.tipo,
            pending.moneda,
            resumeAction
        )
        return false
    }

    private fun beginBalanceConfiguration(
        chatId: Long,
        accountId: Long,
        accountName: String,
        accountType: String,
        accountCurrency: String?,
        resumeAction: ResumeAction
    ) {
        states.put(
            chatId,
            ConversationState(
                step = if (accountType == "debito" && accountCurrency == null) ConversationStep.SELECTING_ACCOUNT_CURRENCY else balanceStep(
                    accountCurrency
                ),
                accountName = accountName,
                accountType = accountType,
                accountCurrency = accountCurrency,
                accountIdToConfigure = accountId,
                resumeAction = resumeAction
            )
        )
        if (accountType == "debito" && accountCurrency == null) {
            send(chatId, "La cuenta $accountName necesita configurar su moneda.", accountCurrencyKeyboard())
        } else send(
            chatId,
            "La cuenta $accountName necesita configurar sus saldos. ${
                accountBalanceQuestion(
                    accountType,
                    accountCurrency ?: "PEN"
                )
            }"
        )
    }

    private fun showAccountConfirmation(chatId: Long, state: ConversationState) {
        state.step = ConversationStep.ACCOUNT_CONFIRMING
        val balanceLabel = if (state.accountType == "credito") "Deuda actual" else "Saldo actual"
        val balances = if (state.accountType == "debito") {
            val currency = requireNotNull(state.accountCurrency)
            "$balanceLabel $currency: ${
                Money.format(
                    requireNotNull(if (currency == "PEN") state.accountBalancePen else state.accountBalanceUsd),
                    currency
                )
            }"
        } else "$balanceLabel PEN: ${
            Money.format(
                requireNotNull(state.accountBalancePen),
                "PEN"
            )
        }\n$balanceLabel USD: ${Money.format(requireNotNull(state.accountBalanceUsd), "USD")}"
        send(
            chatId,
            "Cuenta: ${state.accountName}\nTipo: ${state.accountType}\n$balances\n\n¿Confirmas estos saldos?",
            keyboard(
                listOf(
                    listOf(button("✅ Confirmar", "account-confirm")),
                    listOf(button("✏️ Corregir saldos", "account-balances-retry")),
                    listOf(button("❌ Cancelar", "cancel"))
                )
            )
        )
    }

    private fun completeAccountSetup(chatId: Long, user: Usuario, state: ConversationState) {
        val balancePen = requireNotNull(state.accountBalancePen)
        val balanceUsd = requireNotNull(state.accountBalanceUsd)
        val existingAccountId = state.accountIdToConfigure
        if (existingAccountId == null) {
            finance.createAccount(
                user,
                requireNotNull(state.accountName),
                requireNotNull(state.accountType),
                state.accountCurrency,
                balancePen,
                balanceUsd
            )
            states.clear(chatId)
            send(chatId, "✅ Cuenta creada con sus saldos actuales. Usa /registrar para añadir un movimiento.")
            return
        }

        finance.configureAccountBalances(user, existingAccountId, state.accountCurrency, balancePen, balanceUsd)
        send(chatId, "✅ Saldos configurados para ${state.accountName}.")
        val nextPending = finance.pendingBalanceAccounts(user).firstOrNull()
        if (nextPending != null) {
            beginBalanceConfiguration(
                chatId,
                requireNotNull(nextPending.id),
                nextPending.nombre,
                nextPending.tipo,
                nextPending.moneda,
                requireNotNull(state.resumeAction)
            )
            return
        }

        val resumeAction = state.resumeAction
        states.clear(chatId)
        when (resumeAction) {
            ResumeAction.SUMMARY -> showSummary(chatId, user)
            ResumeAction.REGISTER -> beginTransaction(chatId)
            ResumeAction.VOUCHER_RETRY -> send(chatId, "Saldos listos. Vuelve a enviar la foto del voucher.")
            null -> Unit
        }
    }

    private fun balanceValidationMessage(): String =
        "Saldo inválido. Ingresa 0 o un número positivo con hasta dos decimales y máximo 9999999999.99."

    private fun accountBalanceQuestion(accountType: String?, currency: String): String {
        val concept = if (accountType == "credito") "deuda pendiente actual" else "saldo disponible actual"
        val currencyName = if (currency == "USD") "dólares (USD)" else "soles (PEN)"
        return "¿Cuál es la $concept en $currencyName? Puedes escribir 0."
    }

    private fun balanceStep(currency: String?): ConversationStep =
        if (currency == "USD") ConversationStep.ACCOUNT_BALANCE_USD else ConversationStep.ACCOUNT_BALANCE_PEN

    private fun accountCurrencyKeyboard(): InlineKeyboardMarkup = keyboard(
        listOf(
            listOf(button("🇵🇪 Soles (PEN)", "account-currency:PEN")),
            listOf(button("🇺🇸 Dólares (USD)", "account-currency:USD"))
        )
    )

    private fun listAccounts(chatId: Long, user: Usuario) {
        val accounts = finance.activeAccounts(user)
        states.put(chatId, ConversationState(ConversationStep.ACCOUNT_MANAGEMENT))
        val message =
            if (accounts.isEmpty()) "No tienes cuentas activas." else accounts.joinToString("\n", "Tus cuentas:\n") {
                "• ${it.nombre} (${it.tipo}${it.moneda?.let { currency -> " $currency" } ?: ""})${if (it.hasConfiguredBalances()) "" else " — saldo pendiente"}"
            }
        val buttons = accounts.map { listOf(button("⚙️ ${it.nombre}", "manage-account:${it.id}")) }.toMutableList()
        buttons += listOf(button("➕ Nueva cuenta", "accounts:new"))
        if (finance.inactiveAccounts(user).isNotEmpty()) buttons += listOf(
            button(
                "🗃️ Ver inactivas",
                "accounts:inactive"
            )
        )
        send(chatId, message, keyboard(buttons))
    }

    private fun showInactiveAccounts(chatId: Long, user: Usuario) {
        val accounts = finance.inactiveAccounts(user)
        states.put(chatId, ConversationState(ConversationStep.ACCOUNT_MANAGEMENT))
        val buttons = accounts.map { listOf(button("♻️ ${it.nombre}", "manage-account:${it.id}")) }.toMutableList()
        buttons += listOf(button("← Cuentas activas", "accounts:active"))
        send(
            chatId,
            if (accounts.isEmpty()) "No tienes cuentas inactivas." else "Cuentas inactivas:",
            keyboard(buttons)
        )
    }

    private fun showAccountManagement(chatId: Long, user: Usuario, accountId: Long) {
        val account = finance.managedAccount(accountId, user) ?: run { send(chatId, "Esa cuenta no existe."); return }
        states.put(chatId, ConversationState(ConversationStep.ACCOUNT_MANAGEMENT, managedAccountId = accountId))
        val balance = if (account.hasConfiguredBalances()) {
            if (account.tipo == "debito") Money.format(
                if (account.moneda == "USD") requireNotNull(account.saldoBaseUsd) else requireNotNull(
                    account.saldoBasePen
                ), requireNotNull(account.moneda)
            )
            else "PEN ${Money.format(requireNotNull(account.saldoBasePen), "PEN")} · USD ${
                Money.format(
                    requireNotNull(
                        account.saldoBaseUsd
                    ), "USD"
                )
            }"
        } else "Sin saldo configurado"
        val rows = mutableListOf(
            listOf(button("✏️ Renombrar", "manage:rename")),
            listOf(button("💰 Ajustar saldo", "manage:balance"))
        )
        if (account.tipo == "credito" && account.activo) rows += listOf(button("💳 Pagar desde débito", "manage:pay"))
        rows += listOf(button(if (account.activo) "🗃️ Desactivar" else "♻️ Reactivar", "manage:toggle"))
        rows += listOf(button("← Volver", if (account.activo) "accounts:active" else "accounts:inactive"))
        send(
            chatId,
            "🏦 ${account.nombre}\nTipo: ${account.tipo}${account.moneda?.let { " $it" } ?: ""}\nSaldo configurado: $balance",
            keyboard(rows))
    }

    private fun showAccountAdjustmentConfirmation(
        chatId: Long,
        account: com.andrecarbajal.navifinance.entity.Cuenta,
        state: ConversationState
    ) {
        state.step = ConversationStep.ACCOUNT_ADJUST_CONFIRMING
        val balance = if (account.tipo == "debito") {
            val currency = requireNotNull(account.moneda)
            Money.format(
                requireNotNull(if (currency == "USD") state.accountBalanceUsd else state.accountBalancePen),
                currency
            )
        } else "PEN ${Money.format(requireNotNull(state.accountBalancePen), "PEN")} · USD ${
            Money.format(
                requireNotNull(
                    state.accountBalanceUsd
                ), "USD"
            )
        }"
        send(
            chatId,
            "Nuevo saldo actual para ${account.nombre}: $balance\n\nEsto conserva el historial y usa este valor como nueva referencia. ¿Confirmas?",
            keyboard(
                listOf(
                    listOf(button("✅ Confirmar ajuste", "account-adjust-confirm")),
                    listOf(button("❌ Cancelar", "cancel"))
                )
            )
        )
    }

    private fun summary(chatId: Long, user: Usuario) {
        if (!ensureBalancesConfigured(chatId, user, ResumeAction.SUMMARY)) return
        showSummary(chatId, user)
    }

    private fun showSummary(chatId: Long, user: Usuario) {
        val month = YearMonth.now()
        val summaries = finance.monthlyAccountSummaries(user, month)
        if (summaries.isEmpty()) {
            send(chatId, "No tienes cuentas activas. Usa /cuentas para crear una.")
            return
        }
        send(chatId, "Resumen del mes ${month.monthValue.toString().padStart(2, '0')}/${month.year}:")
        summaries.forEach { accountSummary ->
            send(
                chatId,
                formatAccountSummary(accountSummary),
                keyboard(
                    listOf(
                        listOf(
                            button(
                                "📊 Ver detalle por categoría",
                                "summary-detail:${accountSummary.accountId}:$month"
                            )
                        )
                    )
                )
            )
        }
    }

    private fun showCategoryDetail(chatId: Long, user: Usuario, callbackData: String) {
        val request = parseSummaryDetailCallback(callbackData) ?: run {
            send(chatId, "El detalle solicitado no es válido.")
            return
        }
        val detail = finance.monthlyCategorySummary(user, request.accountId, request.month) ?: run {
            send(chatId, "Esa cuenta no está disponible.")
            return
        }
        sendLongMessage(chatId, formatCategorySummary(detail))
    }

    private fun beginTransaction(chatId: Long, draft: TransactionDraft = TransactionDraft(), user: Usuario? = null) {
        val state = ConversationState(ConversationStep.SELECTING_TYPE, draft)
        states.put(chatId, state)
        if (draft.type != null && user != null) chooseAccount(chatId, user, state)
        else send(
            chatId,
            "¿Qué deseas registrar?",
            keyboard(
                listOf(
                    listOf(button("➖ Retiro", "type:retiro"), button("➕ Abono", "type:abono")),
                    listOf(button("💳 Pagar tarjeta", "payment:new"))
                )
            )
        )
    }

    private fun beginNewCreditPayment(chatId: Long, user: Usuario) {
        val sources = finance.activeDebitAccounts(user)
        if (sources.isEmpty()) {
            send(chatId, "Necesitas una cuenta de débito configurada para pagar la tarjeta."); return
        }
        val state = ConversationState(ConversationStep.SELECTING_PAYMENT_SOURCE, TransactionDraft(type = "abono"))
        states.put(chatId, state)
        send(
            chatId,
            "Elige la cuenta de débito desde la que pagarás.",
            keyboard(sources.map { listOf(button("${it.nombre} (${it.moneda})", "payment-source-new:${it.id}")) })
        )
    }

    private fun chooseCreditAccount(chatId: Long, user: Usuario, state: ConversationState) {
        val accounts = finance.activeAccounts(user).filter { it.tipo == "credito" && it.hasConfiguredBalances() }
        if (accounts.isEmpty()) {
            states.clear(chatId); send(chatId, "No tienes tarjetas de crédito configuradas."); return
        }
        state.step = ConversationStep.SELECTING_ACCOUNT
        send(
            chatId,
            "Elige la tarjeta de crédito que pagarás.",
            keyboard(accounts.map { listOf(button(it.nombre, "payment-credit:${it.id}")) })
        )
    }

    private fun callback(update: Update) {
        val callback = update.callbackQuery
        val chatId = callback.message.chatId
        val user = finance.getOrCreateUser(callback.from.id, callback.from.userName ?: callback.from.firstName)
        val data = callback.data
        if (data.startsWith("summary-detail:")) {
            showCategoryDetail(chatId, user, data)
            return
        }
        val state = states.get(chatId) ?: run {
            send(
                chatId,
                "Este flujo venció. Usa /registrar para comenzar de nuevo."
            ); return
        }
        when {
            data == "accounts:new" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> newAccount(chatId)

            data == "accounts:active" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> listAccounts(chatId, user)

            data == "accounts:inactive" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> showInactiveAccounts(
                chatId,
                user
            )

            data.startsWith("manage-account:") && state.step == ConversationStep.ACCOUNT_MANAGEMENT ->
                data.removePrefix("manage-account:").toLongOrNull()?.let { showAccountManagement(chatId, user, it) }
                    ?: send(chatId, "Esa cuenta no es válida.")

            data == "manage:rename" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> {
                state.step = ConversationStep.ACCOUNT_RENAMING
                send(chatId, "Escribe el nuevo nombre de la cuenta.")
            }

            data == "manage:balance" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> {
                val account = state.managedAccountId?.let { finance.managedAccount(it, user) } ?: run {
                    send(
                        chatId,
                        "Esa cuenta no existe."
                    ); return
                }
                state.accountBalancePen = null
                state.accountBalanceUsd = null
                state.step =
                    if (account.tipo == "debito" && account.moneda == "USD") ConversationStep.ACCOUNT_ADJUSTING_USD else ConversationStep.ACCOUNT_ADJUSTING_PEN
                send(
                    chatId,
                    "Indica el saldo actual en ${if (state.step == ConversationStep.ACCOUNT_ADJUSTING_USD) "USD" else "PEN"}. Este ajuste no elimina movimientos previos."
                )
            }

            data == "manage:toggle" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> {
                val account = state.managedAccountId?.let { finance.managedAccount(it, user) } ?: run {
                    send(
                        chatId,
                        "Esa cuenta no existe."
                    ); return
                }
                val willBeActive = !account.activo
                finance.setAccountActive(user, requireNotNull(account.id), willBeActive)
                send(
                    chatId,
                    if (willBeActive) "✅ Cuenta reactivada." else "✅ Cuenta desactivada; su historial se conserva."
                )
                listAccounts(chatId, user)
            }

            data == "manage:pay" && state.step == ConversationStep.ACCOUNT_MANAGEMENT -> {
                state.draft.type = "abono"
                state.draft.accountId = state.managedAccountId
                choosePaymentSource(chatId, user, state)
            }

            data == "account-adjust-confirm" && state.step == ConversationStep.ACCOUNT_ADJUST_CONFIRMING -> {
                finance.adjustAccountBalances(
                    user,
                    requireNotNull(state.managedAccountId),
                    requireNotNull(state.accountBalancePen),
                    requireNotNull(state.accountBalanceUsd)
                )
                send(chatId, "✅ Saldo ajustado. Los movimientos anteriores se conservaron.")
                showAccountManagement(chatId, user, requireNotNull(state.managedAccountId))
            }

            data == "payment:new" && state.step == ConversationStep.SELECTING_TYPE -> beginNewCreditPayment(
                chatId,
                user
            )

            data.startsWith("payment-source-new:") && state.step == ConversationStep.SELECTING_PAYMENT_SOURCE -> {
                finance.account(data.removePrefix("payment-source-new:").toLongOrNull() ?: -1, user)
                    ?.takeIf { it.tipo == "debito" && it.hasConfiguredBalances() }?.let { source ->
                    state.draft.paymentSourceAccountId = source.id
                    state.draft.paymentCurrency = source.moneda
                    chooseCreditAccount(chatId, user, state)
                } ?: send(chatId, "Esa cuenta de débito no está disponible.")
            }

            data.startsWith("payment-credit:") && state.step == ConversationStep.SELECTING_ACCOUNT -> {
                finance.account(data.removePrefix("payment-credit:").toLongOrNull() ?: -1, user)
                    ?.takeIf { it.tipo == "credito" && it.hasConfiguredBalances() }?.let { credit ->
                    state.draft.accountId = credit.id
                    chooseDebtCurrency(chatId, state)
                } ?: send(chatId, "Esa tarjeta no está disponible.")
            }

            data.startsWith("account-type:") && state.step == ConversationStep.ACCOUNT_TYPE -> {
                state.accountType = data.removePrefix("account-type:")
                if (state.accountType == "debito") {
                    state.step = ConversationStep.SELECTING_ACCOUNT_CURRENCY
                    send(chatId, "Elige la moneda de esta cuenta de débito.", accountCurrencyKeyboard())
                } else {
                    state.step = ConversationStep.ACCOUNT_BALANCE_PEN
                    send(chatId, accountBalanceQuestion(state.accountType, "PEN"))
                }
            }

            data.startsWith("account-currency:") && state.step == ConversationStep.SELECTING_ACCOUNT_CURRENCY -> {
                data.removePrefix("account-currency:").takeIf { it in setOf("PEN", "USD") }?.let { currency ->
                    state.accountCurrency = currency
                    state.step = balanceStep(currency)
                    send(chatId, accountBalanceQuestion(state.accountType, currency))
                } ?: send(chatId, "Elige una moneda válida.")
            }

            data == "account-confirm" && state.step == ConversationStep.ACCOUNT_CONFIRMING ->
                completeAccountSetup(chatId, user, state)

            data == "account-balances-retry" && state.step == ConversationStep.ACCOUNT_CONFIRMING -> {
                state.accountBalancePen = null
                state.accountBalanceUsd = null
                state.step =
                    if (state.accountType == "debito") ConversationStep.SELECTING_ACCOUNT_CURRENCY else ConversationStep.ACCOUNT_BALANCE_PEN
                if (state.accountType == "debito") send(
                    chatId,
                    "Vamos a corregirla. Elige nuevamente la moneda.",
                    accountCurrencyKeyboard()
                )
                else send(chatId, "Vamos a corregirlos. ${accountBalanceQuestion(state.accountType, "PEN")}")
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

            data.startsWith("payment-source:") && state.step == ConversationStep.SELECTING_PAYMENT_SOURCE -> {
                finance.account(data.removePrefix("payment-source:").toLongOrNull() ?: -1, user)?.takeIf {
                    it.tipo == "debito" && it.hasConfiguredBalances()
                }?.let { sourceAccount ->
                    state.draft.paymentSourceAccountId = sourceAccount.id
                    state.draft.paymentCurrency = sourceAccount.moneda
                    chooseDebtCurrency(chatId, state)
                } ?: send(chatId, "Esa cuenta de origen no está disponible. Elige otra.")
            }

            data.startsWith("payment-debt-currency:") && state.step == ConversationStep.SELECTING_DEBT_CURRENCY -> {
                data.removePrefix("payment-debt-currency:").takeIf { it in setOf("PEN", "USD") }?.let { currency ->
                    state.draft.currency = currency
                    continueCreditPayment(chatId, user, state)
                } ?: send(chatId, "Elige una moneda de deuda válida.")
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
                val name = input.trim().take(80)
                if (name.isBlank()) {
                    send(chatId, "El nombre de la cuenta no puede quedar vacío.")
                    return
                }
                state.accountName = name; state.step = ConversationStep.ACCOUNT_TYPE; send(
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

            ConversationStep.ACCOUNT_RENAMING -> {
                val accountId = state.managedAccountId ?: run { send(chatId, "Esa cuenta no existe."); return }
                runCatching { finance.renameAccount(user, accountId, input) }
                    .onSuccess { account ->
                        send(chatId, "✅ Cuenta renombrada.")
                        showAccountManagement(chatId, user, requireNotNull(account.id))
                    }
                    .onFailure { send(chatId, "El nombre de la cuenta no puede quedar vacío.") }
            }

            ConversationStep.ACCOUNT_ADJUSTING_PEN -> Money.parseNonNegative(input)?.let { balance ->
                state.accountBalancePen = balance
                val account = state.managedAccountId?.let { finance.managedAccount(it, user) } ?: return
                if (account.tipo == "debito") {
                    state.accountBalanceUsd = BigDecimal.ZERO
                    showAccountAdjustmentConfirmation(chatId, account, state)
                } else {
                    state.step = ConversationStep.ACCOUNT_ADJUSTING_USD
                    send(chatId, "Indica el saldo actual en USD.")
                }
            } ?: send(chatId, balanceValidationMessage())

            ConversationStep.ACCOUNT_ADJUSTING_USD -> Money.parseNonNegative(input)?.let { balance ->
                state.accountBalanceUsd = balance
                val account = state.managedAccountId?.let { finance.managedAccount(it, user) } ?: return
                if (account.tipo == "debito") state.accountBalancePen = BigDecimal.ZERO
                showAccountAdjustmentConfirmation(chatId, account, state)
            } ?: send(chatId, balanceValidationMessage())

            ConversationStep.ACCOUNT_BALANCE_PEN -> Money.parseNonNegative(input)?.let {
                state.accountBalancePen = it
                if (state.accountType == "debito") {
                    state.accountBalanceUsd = BigDecimal.ZERO
                    showAccountConfirmation(chatId, state)
                } else {
                    state.step = ConversationStep.ACCOUNT_BALANCE_USD
                    send(chatId, accountBalanceQuestion(state.accountType, "USD"))
                }
            } ?: send(chatId, balanceValidationMessage())

            ConversationStep.ACCOUNT_BALANCE_USD -> Money.parseNonNegative(input)?.let {
                state.accountBalanceUsd = it
                if (state.accountType == "debito") state.accountBalancePen = BigDecimal.ZERO
                showAccountConfirmation(chatId, state)
            } ?: send(chatId, balanceValidationMessage())

            ConversationStep.EXPECTING_AMOUNT -> Money.parse(input)?.let {
                state.draft.amount = it
                if (state.editingField == "amount") finishEdit(chatId, user, state)
                else continueAfterAmount(chatId, user, state)
            }
                ?: send(chatId, "Monto inválido. Ingresa un número positivo con hasta dos decimales.")

            ConversationStep.EXPECTING_PAYMENT_AMOUNT -> Money.parse(input)?.let {
                state.draft.paymentAmount = it
                continueCreditPayment(chatId, user, state)
            } ?: send(chatId, "Monto inválido. Ingresa un número positivo con hasta dos decimales.")

            ConversationStep.EXPECTING_EXCHANGE_RATE -> Money.parseExchangeRate(input)?.let {
                state.draft.exchangeRate = it
                state.draft.amount = calculateAppliedPayment(
                    requireNotNull(state.draft.paymentAmount),
                    requireNotNull(state.draft.paymentCurrency),
                    state.draft.currency,
                    it
                )
                chooseCategory(chatId, user, state)
            } ?: send(
                chatId,
                "Tipo de cambio inválido. Usa un número positivo con hasta seis decimales, por ejemplo 3.750000."
            )

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
        val account = state.draft.accountId?.let { finance.account(it, user) } ?: return
        if (account.tipo == "debito") {
            state.draft.currency = requireNotNull(account.moneda)
            if (state.draft.amount == null) askAmount(chatId, state) else chooseCategory(chatId, user, state)
        } else if (state.draft.type == "abono") {
            if (state.draft.paymentAmount == null && state.draft.amount != null) {
                state.draft.paymentAmount = state.draft.amount
                state.draft.amount = null
            }
            choosePaymentSource(chatId, user, state)
        } else if (nextStepAfterAccount(state.draft) == ConversationStep.EXPECTING_AMOUNT) {
            askAmount(chatId, state)
        } else {
            chooseCategory(chatId, user, state)
        }
    }

    private fun continueAfterAmount(chatId: Long, user: Usuario, state: ConversationState) {
        val account = state.draft.accountId?.let { finance.account(it, user) } ?: return
        if (account.tipo == "debito") {
            state.draft.currency = requireNotNull(account.moneda)
            chooseCategory(chatId, user, state)
        } else {
            state.step = ConversationStep.SELECTING_CURRENCY
            askCurrency(chatId)
        }
    }

    private fun choosePaymentSource(chatId: Long, user: Usuario, state: ConversationState) {
        val sources = finance.activeDebitAccounts(user)
        if (sources.isEmpty()) {
            states.clear(chatId)
            send(chatId, "Necesitas una cuenta de débito configurada para pagar la tarjeta. Usa /cuenta_nueva.")
            return
        }
        state.step = ConversationStep.SELECTING_PAYMENT_SOURCE
        send(
            chatId,
            "Elige la cuenta de débito desde la que pagarás.",
            keyboard(sources.map { listOf(button("${it.nombre} (${it.moneda})", "payment-source:${it.id}")) })
        )
    }

    private fun chooseDebtCurrency(chatId: Long, state: ConversationState) {
        state.step = ConversationStep.SELECTING_DEBT_CURRENCY
        send(
            chatId,
            "¿En qué moneda deseas reducir la deuda?",
            keyboard(
                listOf(
                    listOf(button("🇵🇪 Deuda en PEN", "payment-debt-currency:PEN")),
                    listOf(button("🇺🇸 Deuda en USD", "payment-debt-currency:USD"))
                )
            )
        )
    }

    private fun continueCreditPayment(chatId: Long, user: Usuario, state: ConversationState) {
        val paymentAmount = state.draft.paymentAmount
        if (paymentAmount == null) {
            state.step = ConversationStep.EXPECTING_PAYMENT_AMOUNT
            send(chatId, "¿Cuál es el monto que pagarás desde la cuenta de débito?")
            return
        }
        val paidCurrency = requireNotNull(state.draft.paymentCurrency)
        if (paidCurrency == state.draft.currency) {
            state.draft.exchangeRate = null
            state.draft.amount = paymentAmount
            chooseCategory(chatId, user, state)
        } else {
            state.step = ConversationStep.EXPECTING_EXCHANGE_RATE
            send(chatId, "Indica el tipo de cambio: 1 USD = S/ X.")
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
        val paymentDetails = if (state.draft.paymentSourceAccountId != null) {
            val source =
                finance.account(requireNotNull(state.draft.paymentSourceAccountId), user)?.nombre ?: "Sin cuenta"
            "\nPagado desde: $source\nMonto pagado: ${
                Money.format(
                    requireNotNull(state.draft.paymentAmount),
                    requireNotNull(state.draft.paymentCurrency)
                )
            }" +
                    "\nDeuda reducida: ${Money.format(requireNotNull(state.draft.amount), state.draft.currency)}" +
                    (state.draft.exchangeRate?.let {
                        "\nTipo de cambio: 1 USD = S/ ${
                            it.stripTrailingZeros().toPlainString()
                        }"
                    } ?: "")
        } else ""
        val confirmationButtons = if (state.draft.paymentSourceAccountId == null) {
            listOf(
                listOf(button("✅ Confirmar", "confirm"), button("✏️ Editar", "edit")),
                listOf(button("❌ Cancelar", "cancel"))
            )
        } else listOf(
            listOf(button("✅ Confirmar", "confirm")),
            listOf(button("❌ Cancelar y registrar de nuevo", "cancel"))
        )
        send(
            chatId,
            "Resumen:\nTipo: ${state.draft.type}\nCuenta: $account\nMonto: ${
                Money.format(
                    requireNotNull(state.draft.amount),
                    state.draft.currency
                )
            }$paymentDetails\nCategoría: $category\nDescripción: $description\nOrigen: ${state.draft.source}\nFecha: ${
                formatDisplayDate(state.draft.date)
            }\n\n¿Confirmas?",
            keyboard(confirmationButtons)
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
        val user = finance.getOrCreateUser(message.from.id, message.from.userName ?: message.from.firstName)
        if (!ensureAccountRegistered(chatId, user)) return
        if (!ensureBalancesConfigured(chatId, user, ResumeAction.VOUCHER_RETRY)) return

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

    private fun sendLongMessage(chatId: Long, text: String) {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        text.lineSequence().forEach { line ->
            if (current.isNotEmpty() && current.length + line.length + 1 > TELEGRAM_MESSAGE_LIMIT) {
                chunks += current.toString().trimEnd()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) chunks += current.toString().trimEnd()
        chunks.forEach { send(chatId, it) }
    }

    private fun button(text: String, data: String): InlineKeyboardButton =
        InlineKeyboardButton(text).also { it.callbackData = data }

    private fun keyboard(rows: List<List<InlineKeyboardButton>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(rows.map(::InlineKeyboardRow))
}

internal fun nextStepAfterAccount(draft: TransactionDraft): ConversationStep =
    if (draft.amount == null) ConversationStep.EXPECTING_AMOUNT else ConversationStep.SELECTING_CATEGORY

internal fun accountRegistrationRequired(activeAccountCount: Int): Boolean = activeAccountCount == 0

internal data class SummaryDetailRequest(val accountId: Long, val month: YearMonth)

internal fun parseSummaryDetailCallback(data: String): SummaryDetailRequest? = runCatching {
    val parts = data.removePrefix("summary-detail:").split(':')
    require(data.startsWith("summary-detail:") && parts.size == 2)
    SummaryDetailRequest(parts[0].toLong(), YearMonth.parse(parts[1]))
}.getOrNull()

internal fun formatAccountSummary(summary: AccountSummary): String = buildString {
    append("🏦 ${summary.accountName} (${if (summary.accountType == "credito") "Crédito" else "Débito"})")
    summary.currencies.forEach { currency ->
        append("\n\n${if (currency.currency == "USD") "🇺🇸 USD" else "🇵🇪 PEN"}")
        append("\nAbonos del mes: ${Money.format(currency.income, currency.currency)}")
        append("\nRetiros del mes: ${Money.format(currency.expenses, currency.currency)}")
        val label = when {
            summary.accountType == "credito" && currency.currentBalance.signum() < 0 -> "Saldo a favor"
            summary.accountType == "credito" -> "Deuda pendiente"
            currency.currentBalance.signum() < 0 -> "Sobregiro"
            else -> "Saldo disponible"
        }
        append("\n$label: ${Money.format(currency.currentBalance.abs(), currency.currency)}")
    }
    if (summary.accountType == "credito" && summary.paymentTotals.isNotEmpty()) {
        append("\n\nPagos realizados desde débito:")
        summary.paymentTotals.forEach { payment ->
            append("\n• ${Money.format(payment.amount, payment.currency)}")
        }
    }
}

internal fun formatCategorySummary(summary: AccountCategorySummary): String = buildString {
    append(
        "Detalle por categoría — ${summary.accountName} " +
                "(${summary.month.monthValue.toString().padStart(2, '0')}/${summary.month.year})"
    )
    if (summary.entries.isEmpty()) {
        append("\n\nNo registraste movimientos en esta cuenta durante el mes.")
        return@buildString
    }
    listOf("PEN", "USD").forEach { currency ->
        val entries = summary.entries.filter { it.currency == currency }
        if (entries.isEmpty()) return@forEach
        append("\n\n${if (currency == "USD") "🇺🇸 USD" else "🇵🇪 PEN"}")
        entries.forEach { entry ->
            append("\n• ${entry.category}")
            append("\n  Abonos: ${Money.format(entry.income, currency)}")
            append("\n  Retiros: ${Money.format(entry.expenses, currency)}")
        }
    }
    if (summary.creditPayments.isNotEmpty()) {
        append("\n\nPagos de tarjeta:")
        summary.creditPayments.forEach { payment ->
            append(
                "\n• ${payment.category}: ${
                    Money.format(
                        payment.paidAmount,
                        payment.paidCurrency
                    )
                } desde ${payment.sourceAccountName ?: "cuenta de débito"}"
            )
            append(" → aplica ${Money.format(payment.appliedAmount, payment.debtCurrency)}")
            payment.exchangeRate?.let { append(" (1 USD = S/ ${it.stripTrailingZeros().toPlainString()})") }
        }
    }
    if (summary.debitPayments.isNotEmpty()) {
        append("\n\nPagos a tarjeta:")
        summary.debitPayments.forEach { payment ->
            append(
                "\n• ${payment.category}: ${
                    Money.format(
                        payment.paidAmount,
                        payment.paidCurrency
                    )
                } → ${payment.targetAccountName ?: "tarjeta de crédito"}"
            )
        }
    }
}

internal fun botCommands(): List<BotCommand> = listOf(
    BotCommand("start", "Iniciar el bot"),
    BotCommand("cuentas", "Gestionar tus cuentas"),
    BotCommand("registrar", "Registrar movimientos o pagar tarjeta"),
    BotCommand("resumen", "Ver el resumen del mes"),
    BotCommand("cancelar", "Cancelar el flujo actual")
)

private val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

internal fun formatDisplayDate(date: LocalDate): String = date.format(DISPLAY_DATE_FORMATTER)

private const val TELEGRAM_MESSAGE_LIMIT = 4000
