package com.andrecarbajal.navifinance.repository

import com.andrecarbajal.navifinance.entity.*
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class UsuarioRepository : PanacheRepository<Usuario> {
    fun findByTelegramId(telegramId: Long): Usuario? = find("telegramId", telegramId).firstResult()
}

@ApplicationScoped
class CuentaRepository : PanacheRepository<Cuenta> {
    fun activeFor(usuario: Usuario): List<Cuenta> = list("usuario = ?1 and activo = true order by nombre", usuario)
    fun belongsTo(id: Long, usuario: Usuario): Cuenta? = find("id = ?1 and usuario = ?2 and activo = true", id, usuario).firstResult()
}

@ApplicationScoped
class CategoriaRepository : PanacheRepository<Categoria> {
    fun forUser(usuario: Usuario): List<Categoria> = list("usuario = ?1 order by nombre", usuario)
    fun belongsTo(id: Long, usuario: Usuario): Categoria? = find("id = ?1 and usuario = ?2", id, usuario).firstResult()
    fun byName(usuario: Usuario, nombre: String): Categoria? = find("usuario = ?1 and nombre = ?2", usuario, nombre).firstResult()
}

@ApplicationScoped
class TransaccionRepository : PanacheRepository<Transaccion> {
    fun forPeriod(usuario: Usuario, start: java.time.LocalDate, end: java.time.LocalDate): List<Transaccion> =
        list("usuario = ?1 and fecha between ?2 and ?3", usuario, start, end)
}
