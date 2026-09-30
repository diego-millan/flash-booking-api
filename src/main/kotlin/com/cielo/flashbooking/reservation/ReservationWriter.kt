package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.CapacityExceededException
import com.cielo.flashbooking.error.NotFoundException
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
}
