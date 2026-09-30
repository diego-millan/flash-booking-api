package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.CapacityExceededException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.error.ReservationExpiredException
import com.cielo.flashbooking.event.EventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ReservationWriter(
    private val eventRepository: EventRepository,
    private val reservationRepository: ReservationRepository,
) {

    @Transactional
    fun write(eventId: Long, quantity: Int, idempotencyKey: String, expiresAt: Instant): Reservation {
        val updatedRows = eventRepository.addReserved(eventId, quantity)
        if (updatedRows == 0) {
            val event = eventRepository.findById(eventId).orElseThrow { NotFoundException("eventId", eventId) }
            throw CapacityExceededException(eventId, quantity, event.capacity - event.reserved)
        }

        return reservationRepository.saveAndFlush(
            Reservation(
                eventId = eventId,
                quantity = quantity,
                idempotencyKey = idempotencyKey,
                expiresAt = expiresAt,
            ),
        )
    }

    @Transactional
    fun cancel(id: Long): Reservation {
        val updatedRows = reservationRepository.markCancelled(id)
        val reservation = reservationRepository.findById(id).orElseThrow { NotFoundException("reservationId", id) }

        if (updatedRows == 0) {
            if (reservation.status == ReservationStatus.EXPIRED) {
                throw ReservationExpiredException(id)
            }
            return reservation
        }

        eventRepository.releaseReserved(reservation.eventId, reservation.quantity)
        return reservation
    }

    @Transactional
    fun expire(id: Long): Reservation {
        val updatedRows = reservationRepository.markExpired(id)
        val reservation = reservationRepository.findById(id).orElseThrow { NotFoundException("reservationId", id) }

        if (updatedRows == 1) {
            eventRepository.releaseReserved(reservation.eventId, reservation.quantity)
        }
        return reservation
    }
}
