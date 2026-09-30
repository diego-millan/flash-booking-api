package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.IdempotencyConflictException
import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.error.QuantityLimitExceededException
import com.cielo.flashbooking.error.ReservationExpiredException
import com.cielo.flashbooking.error.ValidationException
import com.cielo.flashbooking.event.EventRepository
import com.cielo.flashbooking.reservation.dto.CreateReservationRequest
import com.cielo.flashbooking.reservation.dto.CreateReservationResult
import com.cielo.flashbooking.reservation.dto.ReservationResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ReservationService(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
    private val reservationWriter: ReservationWriter,
    @Value("\${flash-booking.reservation.max-quantity}") private val maxQuantity: Int,
    @Value("\${flash-booking.reservation.ttl-minutes}") private val ttlMinutes: Long,
) {

    fun create(eventId: Long, request: CreateReservationRequest, idempotencyKey: String): CreateReservationResult {
        eventRepository.findById(eventId).orElseThrow { NotFoundException("eventId", eventId) }

        val quantity = request.quantity
        if (quantity <= 0) {
            throw InvalidQuantityException("quantity", quantity)
        }
        if (quantity > maxQuantity) {
            throw QuantityLimitExceededException(quantity, maxQuantity)
        }
        if (idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency-Key must not be blank", mapOf("Idempotency-Key" to idempotencyKey))
        }

        reservationRepository.findByIdempotencyKey(idempotencyKey)?.let { return replay(it, eventId) }

        val expiresAt = Instant.now().plus(Duration.ofMinutes(ttlMinutes))
        val reservation = try {
            reservationWriter.write(eventId, quantity, idempotencyKey, expiresAt)
        } catch (ex: DataIntegrityViolationException) {
            // unique violations are translated as DataIntegrityViolationException in this path,
            // so the key is re-read to tell a concurrent duplicate from any other integrity error
            val existing = reservationRepository.findByIdempotencyKey(idempotencyKey) ?: throw ex
            return replay(existing, eventId)
        }

        return CreateReservationResult(reservation.toResponse(), replayed = false)
    }

    private fun replay(existing: Reservation, eventId: Long): CreateReservationResult {
        if (existing.eventId != eventId) {
            throw IdempotencyConflictException(eventId, existing.eventId)
        }
        return CreateReservationResult(existing.toResponse(), replayed = true)
    }

    fun get(id: Long): ReservationResponse =
        reservationRepository.findById(id).orElseThrow { NotFoundException("reservationId", id) }.toResponse()

    fun cancel(id: Long): ReservationResponse {
        val existing = reservationRepository.findById(id).orElseThrow { NotFoundException("reservationId", id) }
        return when (existing.status) {
            ReservationStatus.CANCELLED -> existing.toResponse()
            ReservationStatus.EXPIRED -> throw ReservationExpiredException(id)
            else -> reservationWriter.cancel(id).toResponse()
        }
    }
}

internal fun Reservation.toResponse() = ReservationResponse(
    id = requireNotNull(id),
    eventId = eventId,
    quantity = quantity,
    status = status,
    expiresAt = expiresAt,
    createdAt = createdAt,
)
