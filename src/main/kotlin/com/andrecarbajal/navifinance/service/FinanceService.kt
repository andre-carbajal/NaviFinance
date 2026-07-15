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
import java.time.LocalDate
import java.time.YearMonth

data class CurrencySummary(val currency: String, val income: BigDecimal, val expenses: BigDecimal) {
    val balance: BigDecimal get() = income.subtract(expenses)
}

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
    fun categories(user: Usuario): List<Categoria> = categorias.forUser(user)
    fun account(id: Long, user: Usuario): Cuenta? = cuentas.belongsTo(id, user)
    fun category(id: Long, user: Usuario): Categoria? = categorias.belongsTo(id, user)

    @Transactional
    fun createAccount(user: Usuario, name: String, type: String): Cuenta {
        require(type in setOf("debito", "credito"))
        return Cuenta().also { account ->
            account.usuario = user
            account.nombre = name.trim()
            account.tipo = type
            cuentas.persist(account)
        }
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

    fun monthlySummary(user: Usuario, month: YearMonth = YearMonth.now()): List<CurrencySummary> {
        val rows = transacciones.forPeriod(user, month.atDay(1), month.atEndOfMonth())
        return rows.groupBy { it.moneda }.map { (currency, values) ->
            CurrencySummary(
                currency,
                values.filter { it.tipo == "abono" }.fold(BigDecimal.ZERO) { total, tx -> total + tx.monto },
                values.filter { it.tipo == "retiro" }.fold(BigDecimal.ZERO) { total, tx -> total + tx.monto }
            )
        }.sortedBy { it.currency }
    }
}

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
