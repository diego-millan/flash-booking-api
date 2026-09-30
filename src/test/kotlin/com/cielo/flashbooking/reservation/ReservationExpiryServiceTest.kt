package com.cielo.flashbooking.reservation

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ReservationExpiryServiceTest {

    private val reservationRepository = mock<ReservationRepository>()
    private val reservationWriter = mock<ReservationWriter>()
    private val expiryService = ReservationExpiryService(reservationRepository, reservationWriter)

    private fun reservation(
        status: ReservationStatus = ReservationStatus.PENDING,
        expiresAt: Instant = Instant.parse("2026-09-30T21:00:00Z"),
    ) = Reservation(
        id = 5L,
        eventId = 1L,
        quantity = 2,
        status = status,
        idempotencyKey = "idem-key-1",
        expiresAt = expiresAt,
        createdAt = Instant.parse("2026-09-30T20:50:00Z"),
    )

    @Test
    fun `should expire every swept reservation when they are past due`() {
        whenever(reservationRepository.findExpiredIds(eq(ReservationStatus.PENDING), any()))
            .thenReturn(listOf(5L, 6L))

        expiryService.sweep()

        verify(reservationWriter).expire(5L)
        verify(reservationWriter).expire(6L)
    }

    @Test
    fun `should do nothing when sweep finds no expired reservation`() {
        whenever(reservationRepository.findExpiredIds(eq(ReservationStatus.PENDING), any()))
            .thenReturn(emptyList())

        expiryService.sweep()

        verify(reservationWriter, never()).expire(any())
    }

    @Test
    fun `should expire on demand when reservation is pending and past due`() {
        val pastDue = reservation(expiresAt = Instant.now().minusSeconds(1))
        whenever(reservationWriter.expire(5L)).thenReturn(reservation(status = ReservationStatus.EXPIRED))

        val result = expiryService.collectIfExpired(pastDue)

        assertEquals(ReservationStatus.EXPIRED, result.status)
        verify(reservationWriter).expire(5L)
    }

    @Test
    fun `should keep reservation untouched when expiresAt is in the future`() {
        val future = reservation(expiresAt = Instant.now().plusSeconds(60))

        val result = expiryService.collectIfExpired(future)

        assertSame(future, result)
        verify(reservationWriter, never()).expire(any())
    }

    @Test
    fun `should keep cancelled reservation untouched when it is past due`() {
        val cancelled = reservation(status = ReservationStatus.CANCELLED, expiresAt = Instant.now().minusSeconds(1))

        val result = expiryService.collectIfExpired(cancelled)

        assertSame(cancelled, result)
        verify(reservationWriter, never()).expire(any())
    }
}
