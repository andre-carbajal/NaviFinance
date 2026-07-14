package com.andrecarbajal.navifinance.entity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*

@Entity
@Table(name = "cuentas")
class Cuenta : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "usuario_id", nullable = false)
    lateinit var usuario: Usuario

    @Column(nullable = false)
    lateinit var nombre: String

    @Column(nullable = false)
    lateinit var tipo: String

    @Column(nullable = false)
    var activo: Boolean = true
}
