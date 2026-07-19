package com.andrecarbajal.navifinance.service

import com.andrecarbajal.navifinance.entity.Categoria
import com.andrecarbajal.navifinance.entity.Cuenta
import com.andrecarbajal.navifinance.entity.Transaccion
import com.andrecarbajal.navifinance.entity.Usuario
import com.andrecarbajal.navifinance.repository.CategoriaRepository
import com.andrecarbajal.navifinance.repository.CuentaRepository
import com.andrecarbajal.navifinance.repository.TransaccionRepository
import com.andrecarbajal.navifinance.repository.UsuarioRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.*

data class AccountCurrencySummary(
    val currency: String,
    val income: BigDecimal,
    val expenses: BigDecimal,
    val currentBalance: BigDecimal
)

data class AccountSummary(
    val accountId: Long,
    val accountName: String,
    val accountType: String,
    val month: YearMonth,
    val currencies: List<AccountCurrencySummary>,
    val paymentTotals: List<PaymentCurrencySummary> = emptyList()
)

data class PaymentCurrencySummary(val currency: String, val amount: BigDecimal)

data class CategoryCurrencySummary(
    val category: String,
    val currency: String,
    val income: BigDecimal,
    val expenses: BigDecimal
)

data class AccountCategorySummary(
    val accountId: Long,
    val accountName: String,
    val month: YearMonth,
    val entries: List<CategoryCurrencySummary>,
    val creditPayments: List<CreditPaymentDetail> = emptyList(),
    val debitPayments: List<DebitPaymentDetail> = emptyList()
)

data class CreditPaymentDetail(
    val category: String,
    val appliedAmount: BigDecimal,
    val debtCurrency: String,
    val paidAmount: BigDecimal,
    val paidCurrency: String,
    val exchangeRate: BigDecimal?,
    val sourceAccountName: String?
)

data class DebitPaymentDetail(
    val category: String,
    val paidAmount: BigDecimal,
    val paidCurrency: String,
    val targetAccountName: String?
)

@ApplicationScoped
@Transactional
class FinanceService(
    private val usuarios: UsuarioRepository,
    private val cuentas: CuentaRepository,
    private val categorias: CategoriaRepository,
    private val transacciones: TransaccionRepository
) {
    companion object {
        private val DEFAULT_CATEGORIES = listOf("Comida", "Transporte", "Servicios", "Ocio", "Salud", "Otros")
        private val MAX_BALANCE = BigDecimal("9999999999.99")
    }

    @Transactional
    fun getOrCreateUser(telegramId: Long, name: String?): Usuario {
        val user = usuarios.findByTelegramId(telegramId) ?: Usuario().also {
            it.telegramId = telegramId
            it.nombre = name?.takeIf(String::isNotBlank)
            usuarios.persist(it)
        }
        DEFAULT_CATEGORIES.forEach { category ->
            if (categorias.byName(user, category) == null) {
                categorias.persist(Categoria().also { it.usuario = user; it.nombre = category })
            }
        }
        return user
    }

    fun activeAccounts(user: Usuario): List<Cuenta> = cuentas.activeFor(user)
    fun inactiveAccounts(user: Usuario): List<Cuenta> = cuentas.inactiveFor(user)
    fun activeDebitAccounts(user: Usuario): List<Cuenta> =
        cuentas.activeFor(user).filter { it.tipo == "debito" && it.hasConfiguredBalances() }

    fun pendingBalanceAccounts(user: Usuario): List<Cuenta> = cuentas.pendingBalancesFor(user)
    fun categories(user: Usuario): List<Categoria> = categorias.forUser(user)
    fun account(id: Long, user: Usuario): Cuenta? = cuentas.belongsTo(id, user)
    fun managedAccount(id: Long, user: Usuario): Cuenta? = cuentas.anyFor(id, user)
    fun category(id: Long, user: Usuario): Categoria? = categorias.belongsTo(id, user)

    @Transactional
    fun createAccount(
        user: Usuario,
        name: String,
        type: String,
        accountCurrency: String?,
        balancePen: BigDecimal,
        balanceUsd: BigDecimal
    ): Cuenta {
        validateAccountConfiguration(type, accountCurrency)
        val configuredAt = LocalDateTime.now()
        return Cuenta().also { account ->
            account.usuario = user
            account.nombre = name.trim()
            account.tipo = type
            account.moneda = accountCurrency
            account.saldoBasePen = normalizedBalance(balancePen)
            account.saldoBaseUsd = normalizedBalance(balanceUsd)
            account.saldoConfiguradoEn = configuredAt
            cuentas.persist(account)
        }
    }

    @Transactional
    fun configureAccountBalances(
        user: Usuario,
        accountId: Long,
        accountCurrency: String?,
        balancePen: BigDecimal,
        balanceUsd: BigDecimal
    ): Cuenta {
        val account = requireNotNull(account(accountId, user))
        require(!account.hasConfiguredBalances())
        validateAccountConfiguration(account.tipo, accountCurrency)
        account.moneda = accountCurrency
        account.saldoBasePen = normalizedBalance(balancePen)
        account.saldoBaseUsd = normalizedBalance(balanceUsd)
        account.saldoConfiguradoEn = LocalDateTime.now()
        return account
    }

    @Transactional
    fun renameAccount(user: Usuario, accountId: Long, name: String): Cuenta {
        val account = requireNotNull(managedAccount(accountId, user))
        val cleaned = name.trim().take(80)
        require(cleaned.isNotBlank())
        account.nombre = cleaned
        return account
    }

    @Transactional
    fun adjustAccountBalances(user: Usuario, accountId: Long, balancePen: BigDecimal, balanceUsd: BigDecimal): Cuenta {
        val account = requireNotNull(managedAccount(accountId, user))
        require(account.hasConfiguredBalances())
        account.saldoBasePen = normalizedBalance(balancePen)
        account.saldoBaseUsd = normalizedBalance(balanceUsd)
        account.saldoConfiguradoEn = LocalDateTime.now()
        return account
    }

    @Transactional
    fun setAccountActive(user: Usuario, accountId: Long, active: Boolean): Cuenta {
        val account = requireNotNull(managedAccount(accountId, user))
        account.activo = active
        return account
    }

    @Transactional
    fun categoryByNameOrCreate(user: Usuario, name: String): Categoria {
        val cleaned = name.trim().take(80)
        require(cleaned.isNotBlank())
        return categorias.byName(user, cleaned) ?: Categoria().also {
            it.usuario = user
            it.nombre = cleaned
            categorias.persist(it)
        }
    }

    @Transactional
    fun saveTransaction(user: Usuario, draft: TransactionDraft): Transaccion {
        val account = requireNotNull(account(requireNotNull(draft.accountId), user))
        require(account.hasConfiguredBalances())
        val category = requireNotNull(category(requireNotNull(draft.categoryId), user))
        require(draft.type in setOf("retiro", "abono"))
        require(draft.currency in setOf("PEN", "USD"))
        require(draft.source.isNotBlank())
        return if (account.tipo == "credito" && draft.type == "abono") {
            saveCreditPayment(user, account, category, draft)
        } else {
            if (account.tipo == "debito") require(draft.currency == account.moneda)
            persistTransaction(
                user, account, category, requireNotNull(draft.type), normalizedAmount(requireNotNull(draft.amount)),
                draft.currency, draft.description, draft.source, draft.date
            )
        }
    }

    fun monthlyAccountSummaries(user: Usuario, month: YearMonth = YearMonth.now()): List<AccountSummary> {
        val accounts = cuentas.activeFor(user).filter(Cuenta::hasConfiguredBalances)
        if (accounts.isEmpty()) return emptyList()
        val monthlyRows = transacciones.forPeriod(user, month.atDay(1), month.atEndOfMonth())
        val earliestSnapshot = accounts.mapNotNull(Cuenta::saldoConfiguradoEn).minOrNull()
        val balanceRows = earliestSnapshot?.let { transacciones.createdAfter(user, it) }.orEmpty()
        return summarizeAccounts(accounts, monthlyRows, balanceRows, month)
    }

    fun monthlyCategorySummary(
        user: Usuario,
        accountId: Long,
        month: YearMonth
    ): AccountCategorySummary? {
        val account = account(accountId, user) ?: return null
        val rows = transacciones.forPeriod(user, month.atDay(1), month.atEndOfMonth())
        return summarizeCategories(account, rows, month)
    }

    private fun normalizedBalance(balance: BigDecimal): BigDecimal {
        require(balance >= BigDecimal.ZERO && balance <= MAX_BALANCE)
        return balance.setScale(2, RoundingMode.UNNECESSARY)
    }

    private fun normalizedAmount(amount: BigDecimal): BigDecimal {
        require(amount > BigDecimal.ZERO && amount <= MAX_BALANCE)
        return amount.setScale(2, RoundingMode.UNNECESSARY)
    }

    private fun validateAccountConfiguration(type: String, accountCurrency: String?) {
        require(type in setOf("debito", "credito"))
        require(
            (type == "debito" && accountCurrency in setOf(
                "PEN",
                "USD"
            )) || (type == "credito" && accountCurrency == null)
        )
    }

    private fun saveCreditPayment(
        user: Usuario,
        creditAccount: Cuenta,
        category: Categoria,
        draft: TransactionDraft
    ): Transaccion {
        val sourceAccount = requireNotNull(account(requireNotNull(draft.paymentSourceAccountId), user))
        require(sourceAccount.id != creditAccount.id)
        require(sourceAccount.tipo == "debito" && sourceAccount.hasConfiguredBalances())
        val paidCurrency = requireNotNull(sourceAccount.moneda)
        require(draft.paymentCurrency == paidCurrency)
        val paidAmount = normalizedAmount(requireNotNull(draft.paymentAmount))
        val exchangeRate = draft.exchangeRate?.let(::normalizedExchangeRate)
        val appliedAmount = calculateAppliedPayment(paidAmount, paidCurrency, draft.currency, exchangeRate)
        val operationId = UUID.randomUUID()
        val creditTransaction = persistTransaction(
            user, creditAccount, category, "abono", appliedAmount, draft.currency, draft.description, draft.source,
            draft.date, paidAmount, paidCurrency, exchangeRate, operationId
        )
        persistTransaction(
            user, sourceAccount, category, "retiro", paidAmount, paidCurrency, draft.description,
            "pago_tarjeta", draft.date, operationId = operationId
        )
        return creditTransaction
    }

    private fun persistTransaction(
        user: Usuario,
        account: Cuenta,
        category: Categoria,
        type: String,
        amount: BigDecimal,
        currency: String,
        description: String?,
        source: String,
        date: LocalDate,
        paidAmount: BigDecimal? = null,
        paidCurrency: String? = null,
        exchangeRate: BigDecimal? = null,
        operationId: UUID? = null
    ): Transaccion = Transaccion().also { transaction ->
        transaction.usuario = user
        transaction.cuenta = account
        transaction.categoria = category
        transaction.tipo = type
        transaction.monto = amount
        transaction.moneda = currency
        transaction.montoPagado = paidAmount
        transaction.monedaPagada = paidCurrency
        transaction.tasaCambio = exchangeRate
        transaction.operacionId = operationId
        transaction.descripcion = description?.takeIf(String::isNotBlank)?.take(240)
        transaction.origen = source.take(40)
        transaction.fecha = date
        transacciones.persist(transaction)
    }

    private fun normalizedExchangeRate(exchangeRate: BigDecimal): BigDecimal {
        require(exchangeRate > BigDecimal.ZERO && exchangeRate <= BigDecimal("999999.999999"))
        return exchangeRate.setScale(6, RoundingMode.UNNECESSARY)
    }
}

internal fun calculateCurrentBalance(
    accountType: String,
    baseBalance: BigDecimal,
    income: BigDecimal,
    expenses: BigDecimal
): BigDecimal = when (accountType) {
    "debito" -> baseBalance + income - expenses
    "credito" -> baseBalance + expenses - income
    else -> error("Unsupported account type: $accountType")
}

internal fun calculateAppliedPayment(
    paidAmount: BigDecimal,
    paidCurrency: String,
    debtCurrency: String,
    exchangeRate: BigDecimal?
): BigDecimal {
    require(paidCurrency in setOf("PEN", "USD") && debtCurrency in setOf("PEN", "USD"))
    val converted = when (paidCurrency) {
        debtCurrency -> {
            require(exchangeRate == null)
            paidAmount
        }

        "PEN" -> paidAmount.divide(requireNotNull(exchangeRate), 2, RoundingMode.HALF_UP)
        else -> paidAmount.multiply(requireNotNull(exchangeRate)).setScale(2, RoundingMode.HALF_UP)
    }
    require(converted > BigDecimal.ZERO && converted <= BigDecimal("9999999999.99"))
    return converted
}

internal fun summarizeAccounts(
    accounts: List<Cuenta>,
    monthlyRows: List<Transaccion>,
    balanceRows: List<Transaccion>,
    month: YearMonth
): List<AccountSummary> = accounts.map { account ->
    val accountId = requireNotNull(account.id)
    val snapshotAt = requireNotNull(account.saldoConfiguradoEn)
    AccountSummary(
        accountId = accountId,
        accountName = account.nombre,
        accountType = account.tipo,
        month = month,
        currencies = (if (account.tipo == "debito") listOf(requireNotNull(account.moneda)) else listOf(
            "PEN",
            "USD"
        )).map { currency ->
            val monthly = monthlyRows.filter { it.cuenta.id == accountId && it.moneda == currency }
            val afterSnapshot = balanceRows.filter {
                it.cuenta.id == accountId && it.moneda == currency && it.creadoEn.isAfter(snapshotAt)
            }
            val income = monthly.totalFor("abono")
            val expenses = monthly.totalFor("retiro")
            val base = if (currency == "PEN") requireNotNull(account.saldoBasePen)
            else requireNotNull(account.saldoBaseUsd)
            AccountCurrencySummary(
                currency = currency,
                income = income,
                expenses = expenses,
                currentBalance = calculateCurrentBalance(
                    account.tipo,
                    base,
                    afterSnapshot.totalFor("abono"),
                    afterSnapshot.totalFor("retiro")
                )
            )
        },
        paymentTotals = if (account.tipo == "credito") {
            monthlyRows.filter { it.cuenta.id == accountId && it.tipo == "abono" && it.montoPagado != null }
                .groupBy { requireNotNull(it.monedaPagada) }
                .map { (currency, values) ->
                    PaymentCurrencySummary(
                        currency,
                        values.sumOf { requireNotNull(it.montoPagado) })
                }
                .sortedBy { it.currency }
        } else emptyList()
    )
}.sortedBy { it.accountName.lowercase() }

internal fun summarizeCategories(
    account: Cuenta,
    rows: List<Transaccion>,
    month: YearMonth
): AccountCategorySummary = AccountCategorySummary(
    accountId = requireNotNull(account.id),
    accountName = account.nombre,
    month = month,
    entries = rows.filter { it.cuenta.id == account.id }.groupBy { it.categoria.nombre to it.moneda }
        .map { (key, values) ->
            CategoryCurrencySummary(
                category = key.first,
                currency = key.second,
                income = values.totalFor("abono"),
                expenses = values.totalFor("retiro")
            )
        }
        .sortedWith(compareBy<CategoryCurrencySummary> { it.currency }.thenBy { it.category.lowercase() }),
    creditPayments = if (account.tipo == "credito") {
        rows.filter { it.cuenta.id == account.id && it.tipo == "abono" && it.montoPagado != null }.map { payment ->
            CreditPaymentDetail(
                category = payment.categoria.nombre,
                appliedAmount = payment.monto,
                debtCurrency = payment.moneda,
                paidAmount = requireNotNull(payment.montoPagado),
                paidCurrency = requireNotNull(payment.monedaPagada),
                exchangeRate = payment.tasaCambio,
                sourceAccountName = payment.operacionId?.let { operationId ->
                    rows.firstOrNull { it.operacionId == operationId && it.cuenta.id != account.id }?.cuenta?.nombre
                }
            )
        }
    } else emptyList(),
    debitPayments = if (account.tipo == "debito") {
        rows.filter { it.cuenta.id == account.id && it.tipo == "retiro" && it.operacionId != null }.map { payment ->
            DebitPaymentDetail(
                category = payment.categoria.nombre,
                paidAmount = payment.monto,
                paidCurrency = payment.moneda,
                targetAccountName = payment.operacionId?.let { operationId ->
                    rows.firstOrNull { it.operacionId == operationId && it.cuenta.id != account.id }?.cuenta?.nombre
                }
            )
        }
    } else emptyList()
)

private fun List<Transaccion>.totalFor(type: String): BigDecimal =
    asSequence().filter { it.tipo == type }.fold(BigDecimal.ZERO) { total, transaction -> total + transaction.monto }

data class TransactionDraft(
    var type: String? = null,
    var accountId: Long? = null,
    var amount: BigDecimal? = null,
    var currency: String = "PEN",
    var paymentSourceAccountId: Long? = null,
    var paymentAmount: BigDecimal? = null,
    var paymentCurrency: String? = null,
    var exchangeRate: BigDecimal? = null,
    var categoryId: Long? = null,
    var description: String? = null,
    var source: String = "manual",
    var date: LocalDate = LocalDate.now()
)
