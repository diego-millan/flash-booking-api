package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.reservation.dto.CreateReservationRequest
import com.cielo.flashbooking.reservation.dto.ReservationResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/events/{eventId}/reservations")
class ReservationController(private val reservationService: ReservationService) {

    @PostMapping
    fun create(
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<ReservationResponse> {
        val result = reservationService.create(eventId, request, idempotencyKey)
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(result.reservation)
    }
}
