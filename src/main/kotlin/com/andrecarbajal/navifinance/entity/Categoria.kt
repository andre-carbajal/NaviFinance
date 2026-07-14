package com.andrecarbajal.navifinance.entity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*

@Entity
@Table(name = "categorias")
class Categoria : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "usuario_id", nullable = false)
    lateinit var usuario: Usuario

    @Column(nullable = false)
    lateinit var nombre: String
}
