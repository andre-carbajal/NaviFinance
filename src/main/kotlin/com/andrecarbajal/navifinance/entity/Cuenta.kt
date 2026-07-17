package com.andrecarbajal.navifinance.entity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "cuentas")
class Cuenta : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    lateinit var usuario: Usuario

    @Column(nullable = false)
    lateinit var nombre: String

    @Column(nullable = false)
    lateinit var tipo: String

    @Column(nullable = false)
    var activo: Boolean = true

    @Column(name = "saldo_base_pen", precision = 12, scale = 2)
    var saldoBasePen: BigDecimal? = null

    @Column(name = "saldo_base_usd", precision = 12, scale = 2)
    var saldoBaseUsd: BigDecimal? = null

    @Column(name = "saldo_configurado_en")
    var saldoConfiguradoEn: LocalDateTime? = null

    fun hasConfiguredBalances(): Boolean =
        saldoBasePen != null && saldoBaseUsd != null && saldoConfiguradoEn != null
}
