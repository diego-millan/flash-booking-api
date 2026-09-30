package com.cielo.flashbooking.reservation

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ReservationRepository : JpaRepository<Reservation, Long> {

    fun findByIdempotencyKey(idempotencyKey: String): Reservation?

    @Query(
        "SELECT r.id FROM Reservation r WHERE r.status = :status AND r.expiresAt < :now ORDER BY r.expiresAt",
    )
    fun findExpiredIds(@Param("status") status: ReservationStatus, @Param("now") now: Instant): List<Long>

    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE reservations SET status = 'CANCELLED' " +
            "WHERE id = :id AND status IN ('PENDING', 'CONFIRMED')",
        nativeQuery = true,
    )
    fun markCancelled(@Param("id") id: Long): Int

    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE reservations SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'",
        nativeQuery = true,
    )
    fun markExpired(@Param("id") id: Long): Int
}
