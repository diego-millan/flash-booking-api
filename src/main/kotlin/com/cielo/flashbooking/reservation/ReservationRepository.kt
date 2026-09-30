package com.cielo.flashbooking.reservation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ReservationRepository : JpaRepository<Reservation, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): Reservation?

    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE reservations SET status = 'CANCELLED' " +
            "WHERE id = :id AND status IN ('PENDING', 'CONFIRMED')",
        nativeQuery = true,
    )
    fun markCancelled(@Param("id") id: Long): Int
}
