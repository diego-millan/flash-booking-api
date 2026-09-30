package com.cielo.flashbooking.reservation.dto

import com.cielo.flashbooking.reservation.ReservationStatus
import java.time.Instant

data class CreateReservationRequest(
    val quantity: Int,
)

data class ReservationResponse(
    val id: Long,
    val eventId: Long,
    val quantity: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class CreateReservationResult(
    val reservation: ReservationResponse,
    val replayed: Boolean,
)
