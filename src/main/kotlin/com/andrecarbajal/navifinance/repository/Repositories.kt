package com.andrecarbajal.navifinance.repository

import com.andrecarbajal.navifinance.entity.Categoria
import com.andrecarbajal.navifinance.entity.Cuenta
import com.andrecarbajal.navifinance.entity.Transaccion
import com.andrecarbajal.navifinance.entity.Usuario
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class UsuarioRepository : PanacheRepository<Usuario> {
    fun findByTelegramId(telegramId: Long): Usuario? = find("telegramId", telegramId).firstResult()
}

@ApplicationScoped
class CuentaRepository : PanacheRepository<Cuenta> {
    fun activeFor(usuario: Usuario): List<Cuenta> = list("usuario = ?1 and activo = true order by nombre", usuario)
    fun inactiveFor(usuario: Usuario): List<Cuenta> = list("usuario = ?1 and activo = false order by nombre", usuario)
    fun belongsTo(id: Long, usuario: Usuario): Cuenta? =
        find("id = ?1 and usuario = ?2 and activo = true", id, usuario).firstResult()

    fun anyFor(id: Long, usuario: Usuario): Cuenta? =
        find("id = ?1 and usuario = ?2", id, usuario).firstResult()

    fun pendingBalancesFor(usuario: Usuario): List<Cuenta> =
        list(
            "usuario = ?1 and activo = true and (saldoConfiguradoEn is null or (tipo = 'debito' and moneda is null)) order by nombre",
            usuario
        )
}

@ApplicationScoped
class CategoriaRepository : PanacheRepository<Categoria> {
    fun forUser(usuario: Usuario): List<Categoria> = list("usuario = ?1 order by nombre", usuario)
    fun belongsTo(id: Long, usuario: Usuario): Categoria? = find("id = ?1 and usuario = ?2", id, usuario).firstResult()
    fun byName(usuario: Usuario, nombre: String): Categoria? =
        find("usuario = ?1 and nombre = ?2", usuario, nombre).firstResult()
}

@ApplicationScoped
class TransaccionRepository : PanacheRepository<Transaccion> {
    fun forPeriod(usuario: Usuario, start: java.time.LocalDate, end: java.time.LocalDate): List<Transaccion> =
        list("usuario = ?1 and fecha between ?2 and ?3", usuario, start, end)

    fun createdAfter(usuario: Usuario, cutoff: java.time.LocalDateTime): List<Transaccion> =
        list("usuario = ?1 and creadoEn > ?2", usuario, cutoff)
}
