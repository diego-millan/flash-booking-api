package com.cielo.flashbooking.reservation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "reservations")
class Reservation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "event_id", nullable = false)
    var eventId: Long = 0,
    @Column(nullable = false)
    var quantity: Int = 0,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReservationStatus = ReservationStatus.PENDING,
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    var idempotencyKey: String = "",
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.EPOCH,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
