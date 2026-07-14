package com.andrecarbajal.navifinance.entity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "usuarios")
class Usuario : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "telegram_id", nullable = false, unique = true)
    var telegramId: Long = 0

    var nombre: String? = null

    @Column(name = "creado_en", nullable = false)
    var creadoEn: LocalDateTime = LocalDateTime.now()
}
