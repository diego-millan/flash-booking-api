package com.cielo.flashbooking.reservation

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ReservationExpiryService(
    private val reservationRepository: ReservationRepository,
    private val reservationWriter: ReservationWriter,
) {

    @Scheduled(
        fixedDelayString = "\${flash-booking.reservation.expiry-scan-ms:5000}",
        initialDelayString = "\${flash-booking.reservation.expiry-initial-delay-ms:1000}",
    )
    fun sweep() {
        reservationRepository.findExpiredIds(ReservationStatus.PENDING, Instant.now())
            .forEach { reservationWriter.expire(it) }
    }

    fun collectIfExpired(reservation: Reservation): Reservation {
        val isExpired = reservation.status == ReservationStatus.PENDING &&
            reservation.expiresAt.isBefore(Instant.now())
        if (!isExpired) return reservation
        return reservationWriter.expire(requireNotNull(reservation.id))
    }
}
