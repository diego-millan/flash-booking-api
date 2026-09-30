package com.cielo.flashbooking.reservation.dto

import com.cielo.flashbooking.reservation.ReservationStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "Payload to reserve seats for an event")
data class CreateReservationRequest(
    @Schema(
        description = "Seats to reserve. Must be between 1 and the per-order limit (10 by default), " +
            "otherwise `422 INVALID_QUANTITY`",
        example = "2",
        minimum = "1",
        maximum = "10",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val quantity: Int,
)

@Schema(description = "A seat reservation. `PENDING` until it expires or is cancelled")
data class ReservationResponse(
    @Schema(description = "Reservation id", example = "34")
    val id: Long,
    @Schema(description = "Event the seats were reserved for", example = "38")
    val eventId: Long,
    @Schema(description = "Number of seats reserved", example = "2")
    val quantity: Int,
    @Schema(description = "PENDING, CANCELLED or EXPIRED", example = "PENDING")
    val status: ReservationStatus,
    @Schema(description = "Instant when the TTL ends and the seats are released", example = "2026-09-30T14:02:48Z")
    val expiresAt: Instant,
    @Schema(description = "Creation instant", example = "2026-09-30T13:52:48Z")
    val createdAt: Instant,
)

data class CreateReservationResult(
    val reservation: ReservationResponse,
    val replayed: Boolean,
)
