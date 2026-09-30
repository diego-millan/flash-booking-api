package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.reservation.dto.CreateReservationRequest
import com.cielo.flashbooking.reservation.dto.ReservationResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class ReservationController(private val reservationService: ReservationService) {

    @PostMapping("/events/{eventId}/reservations")
    fun create(
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<ReservationResponse> {
        val result = reservationService.create(eventId, request, idempotencyKey)
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(result.reservation)
    }

    @GetMapping("/reservations/{id}")
    fun get(@PathVariable id: Long): ReservationResponse = reservationService.get(id)

    @DeleteMapping("/reservations/{id}")
    fun cancel(@PathVariable id: Long): ReservationResponse = reservationService.cancel(id)
}
