package com.cielo.flashbooking.reservation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReservationRepository : JpaRepository<Reservation, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): Reservation?
}
