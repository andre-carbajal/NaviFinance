package com.andrecarbajal.navifinance.entity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "transacciones")
class Transaccion : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "usuario_id", nullable = false)
    lateinit var usuario: Usuario

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "cuenta_id", nullable = false)
    lateinit var cuenta: Cuenta

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "categoria_id", nullable = false)
    lateinit var categoria: Categoria

    @Column(nullable = false)
    lateinit var tipo: String

    @Column(nullable = false, precision = 12, scale = 2)
    lateinit var monto: BigDecimal

    @Column(nullable = false, length = 3)
    var moneda: String = "PEN"

    var descripcion: String? = null

    @Column(nullable = false)
    lateinit var origen: String

    @Column(nullable = false)
    lateinit var fecha: LocalDate

    @Column(name = "creado_en", nullable = false)
    var creadoEn: LocalDateTime = LocalDateTime.now()
}
