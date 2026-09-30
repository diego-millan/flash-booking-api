package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.ErrorResponse
import com.cielo.flashbooking.reservation.dto.CreateReservationRequest
import com.cielo.flashbooking.reservation.dto.ReservationResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
    @Operation(
        summary = "Reserve seats (idempotent)",
        description = "Reserves seats for an event. The `Idempotency-Key` header is required: replaying " +
            "the same key returns `200` with the original reservation instead of creating a new one, " +
            "and reusing a key for a different event fails with `409`.\n\n" +
            "Seats are taken with a conditional update (`reserved + quantity <= capacity`); when there " +
            "is no availability the request fails with `409` and nothing is written. The reservation " +
            "is `PENDING` and expires after the TTL (10 minutes by default), releasing the seats back.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Reservation created (PENDING, seats taken)",
                content = [Content(schema = Schema(implementation = ReservationResponse::class))],
            ),
            ApiResponse(
                responseCode = "200",
                description = "Replay of an already used Idempotency-Key — same reservation, no new seats taken",
                content = [Content(schema = Schema(implementation = ReservationResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR — Idempotency-Key missing, invalid quantity or malformed body",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "NOT_FOUND — event does not exist",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "CAPACITY_EXCEEDED (no seats left) or IDEMPOTENCY_CONFLICT (key used on another event)",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "415",
                description = "UNSUPPORTED_MEDIA_TYPE — request content type is not JSON",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "422",
                description = "INVALID_QUANTITY — quantity below 1 or above the per-order limit",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    fun create(
        @Parameter(description = "Event id", example = "38") @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateReservationRequest,
        @Parameter(
            description = "Idempotency key: unique per reservation attempt, required, reused on retry",
            example = "pedido-42-1",
        )
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): ResponseEntity<ReservationResponse> {
        val result = reservationService.create(eventId, request, idempotencyKey)
        val status = if (result.replayed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(result.reservation)
    }

    @GetMapping("/reservations/{id}")
    @Operation(
        summary = "Get a reservation",
        description = "Returns the reservation. A reservation past its TTL is collected on demand: the " +
            "response shows `status=EXPIRED` and the seats have already been released.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Reservation found",
                content = [Content(schema = Schema(implementation = ReservationResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR — id is not a number",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "NOT_FOUND — reservation does not exist",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    fun get(
        @Parameter(description = "Reservation id", example = "34") @PathVariable id: Long,
    ): ReservationResponse = reservationService.get(id)

    @DeleteMapping("/reservations/{id}")
    @Operation(
        summary = "Cancel a reservation",
        description = "Cancels the reservation and returns the seats exactly once. Cancelling an already " +
            "cancelled reservation answers `200` without releasing seats again; cancelling a " +
            "reservation that already expired answers `409`, because the capacity was released " +
            "by the expiry worker.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Reservation cancelled (also returned when it was already cancelled)",
                content = [Content(schema = Schema(implementation = ReservationResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR — id is not a number",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "NOT_FOUND — reservation does not exist",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "RESERVATION_EXPIRED — already expired, seats were released by the worker",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    fun cancel(
        @Parameter(description = "Reservation id", example = "34") @PathVariable id: Long,
    ): ReservationResponse = reservationService.cancel(id)
}
