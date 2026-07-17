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
    val currencies: List<AccountCurrencySummary>
)

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
    val entries: List<CategoryCurrencySummary>
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
    fun pendingBalanceAccounts(user: Usuario): List<Cuenta> = cuentas.pendingBalancesFor(user)
    fun categories(user: Usuario): List<Categoria> = categorias.forUser(user)
    fun account(id: Long, user: Usuario): Cuenta? = cuentas.belongsTo(id, user)
    fun category(id: Long, user: Usuario): Categoria? = categorias.belongsTo(id, user)

    @Transactional
    fun createAccount(
        user: Usuario,
        name: String,
        type: String,
        balancePen: BigDecimal,
        balanceUsd: BigDecimal
    ): Cuenta {
        require(type in setOf("debito", "credito"))
        val configuredAt = LocalDateTime.now()
        return Cuenta().also { account ->
            account.usuario = user
            account.nombre = name.trim()
            account.tipo = type
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
        balancePen: BigDecimal,
        balanceUsd: BigDecimal
    ): Cuenta {
        val account = requireNotNull(account(accountId, user))
        require(!account.hasConfiguredBalances())
        account.saldoBasePen = normalizedBalance(balancePen)
        account.saldoBaseUsd = normalizedBalance(balanceUsd)
        account.saldoConfiguradoEn = LocalDateTime.now()
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
        val amount = requireNotNull(draft.amount)
        require(amount > BigDecimal.ZERO)
        require(draft.type in setOf("retiro", "abono"))
        require(draft.currency in setOf("PEN", "USD"))
        require(draft.source.isNotBlank())
        return Transaccion().also { transaction ->
            transaction.usuario = user
            transaction.cuenta = account
            transaction.categoria = category
            transaction.tipo = requireNotNull(draft.type)
            transaction.monto = amount
            transaction.moneda = draft.currency
            transaction.descripcion = draft.description?.takeIf(String::isNotBlank)?.take(240)
            transaction.origen = draft.source.take(40)
            transaction.fecha = draft.date
            transacciones.persist(transaction)
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
            .filter { it.cuenta.id == accountId }
        return summarizeCategories(account, rows, month)
    }

    private fun normalizedBalance(balance: BigDecimal): BigDecimal {
        require(balance >= BigDecimal.ZERO && balance <= MAX_BALANCE)
        return balance.setScale(2, RoundingMode.UNNECESSARY)
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
        currencies = listOf("PEN", "USD").map { currency ->
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
        }
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
    entries = rows.groupBy { it.categoria.nombre to it.moneda }
        .map { (key, values) ->
            CategoryCurrencySummary(
                category = key.first,
                currency = key.second,
                income = values.totalFor("abono"),
                expenses = values.totalFor("retiro")
            )
        }
        .sortedWith(compareBy<CategoryCurrencySummary> { it.currency }.thenBy { it.category.lowercase() })
)

private fun List<Transaccion>.totalFor(type: String): BigDecimal =
    asSequence().filter { it.tipo == type }.fold(BigDecimal.ZERO) { total, transaction -> total + transaction.monto }

data class TransactionDraft(
    var type: String? = null,
    var accountId: Long? = null,
    var amount: BigDecimal? = null,
    var currency: String = "PEN",
    var categoryId: Long? = null,
    var description: String? = null,
    var source: String = "manual",
    var date: LocalDate = LocalDate.now()
)
