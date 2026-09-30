package com.cielo.flashbooking.reservation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ReservationExpiryService(
    private val reservationRepository: ReservationRepository,
    private val reservationWriter: ReservationWriter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${flash-booking.reservation.expiry-scan-ms:5000}",
        initialDelayString = "\${flash-booking.reservation.expiry-initial-delay-ms:1000}",
    )
    fun sweep() {
        reservationRepository.findExpiredIds(ReservationStatus.PENDING, Instant.now())
            .forEach { id ->
                logExpired(reservationWriter.expire(id), "worker")
            }
    }

    fun collectIfExpired(reservation: Reservation): Reservation {
        val isExpired = reservation.status == ReservationStatus.PENDING &&
            reservation.expiresAt.isBefore(Instant.now())
        if (!isExpired) return reservation
        return logExpired(reservationWriter.expire(requireNotNull(reservation.id)), "on-demand")
    }

    private fun logExpired(reservation: Reservation, source: String): Reservation {
        logger.info(
            "reservation expired reservationId={} eventId={} quantity={} source={}",
            reservation.id,
            reservation.eventId,
            reservation.quantity,
            source,
        )
        return reservation
    }
}
