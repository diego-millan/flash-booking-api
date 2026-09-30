package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.CapacityExceededException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.error.ReservationExpiredException
import com.cielo.flashbooking.event.Event
import com.cielo.flashbooking.event.EventRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReservationWriterTest {

    private val eventRepository = mock<EventRepository>()
    private val reservationRepository = mock<ReservationRepository>()
    private val writer = ReservationWriter(eventRepository, reservationRepository)

    private val expiresAt = Instant.parse("2026-09-30T21:00:00Z")

    private fun reservationWith(status: ReservationStatus, quantity: Int = 2) = Reservation(
        id = 5L,
        eventId = 1L,
        quantity = quantity,
        status = status,
        idempotencyKey = "idem-key-1",
        expiresAt = expiresAt,
        createdAt = Instant.parse("2026-09-30T20:50:00Z"),
    )

    @Test
    fun `should save reservation when capacity update affects one row`() {
        whenever(eventRepository.addReserved(1L, 2)).thenReturn(1)
        whenever(reservationRepository.saveAndFlush(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Reservation).apply { id = 5L }
        }

        val reservation = writer.write(1L, 2, "idem-key-1", expiresAt)

        assertEquals(5L, reservation.id)
        assertEquals(1L, reservation.eventId)
        assertEquals(2, reservation.quantity)
        assertEquals(ReservationStatus.PENDING, reservation.status)
        assertEquals("idem-key-1", reservation.idempotencyKey)
        assertEquals(expiresAt, reservation.expiresAt)

        val saved = argumentCaptor<Reservation>()
        verify(reservationRepository).saveAndFlush(saved.capture())
        assertEquals(1L, saved.firstValue.eventId)
        assertEquals(2, saved.firstValue.quantity)
    }

    @Test
    fun `should throw capacity exceeded when capacity update affects no rows`() {
        whenever(eventRepository.addReserved(1L, 5)).thenReturn(0)
        whenever(eventRepository.findById(1L))
            .thenReturn(Optional.of(Event(id = 1L, name = "Rock Show", capacity = 10, reserved = 10)))

        val ex = assertFailsWith<CapacityExceededException> { writer.write(1L, 5, "idem-key-1", expiresAt) }

        assertEquals("CAPACITY_EXCEEDED", ex.code)
        assertEquals(409, ex.status.value())
        val expectedDetails = mapOf<String, Any>("eventId" to 1L, "quantity" to 5, "available" to 0)
        assertEquals(expectedDetails, ex.details)
        verify(reservationRepository, org.mockito.kotlin.never()).saveAndFlush(any())
    }

    @Test
    fun `should throw not found when event does not exist before update`() {
        whenever(eventRepository.addReserved(999L, 2)).thenReturn(0)
        whenever(eventRepository.findById(999L)).thenReturn(Optional.empty())

        val ex = assertFailsWith<NotFoundException> { writer.write(999L, 2, "idem-key-1", expiresAt) }

        assertEquals("NOT_FOUND", ex.code)
        assertEquals(mapOf("eventId" to 999L), ex.details)
    }

    @Test
    fun `should keep reservation uncreated when capacity update is skipped`() {
        whenever(eventRepository.addReserved(eq(1L), any())).thenReturn(0)
        whenever(eventRepository.findById(1L))
            .thenReturn(Optional.of(Event(id = 1L, name = "Rock Show", capacity = 10, reserved = 10)))

        assertFailsWith<CapacityExceededException> { writer.write(1L, 2, "idem-key-1", expiresAt) }

        verify(reservationRepository, org.mockito.kotlin.never()).saveAndFlush(any())
    }

    @Test
    fun `should release capacity when cancel update affects one row`() {
        whenever(reservationRepository.markCancelled(5L)).thenReturn(1)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.CANCELLED)))

        val reservation = writer.cancel(5L)

        assertEquals(ReservationStatus.CANCELLED, reservation.status)
        verify(eventRepository).releaseReserved(1L, 2)
    }

    @Test
    fun `should not release capacity when reservation is already cancelled`() {
        whenever(reservationRepository.markCancelled(5L)).thenReturn(0)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.CANCELLED)))

        val reservation = writer.cancel(5L)

        assertEquals(ReservationStatus.CANCELLED, reservation.status)
        verify(eventRepository, never()).releaseReserved(any(), any())
    }

    @Test
    fun `should throw reservation expired when cancel update affects no rows and reservation is expired`() {
        whenever(reservationRepository.markCancelled(5L)).thenReturn(0)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.EXPIRED)))

        val ex = assertFailsWith<ReservationExpiredException> { writer.cancel(5L) }

        assertEquals("RESERVATION_EXPIRED", ex.code)
        assertEquals(409, ex.status.value())
        verify(eventRepository, never()).releaseReserved(any(), any())
    }

    @Test
    fun `should throw not found when cancelling reservation that does not exist`() {
        whenever(reservationRepository.markCancelled(999L)).thenReturn(0)
        whenever(reservationRepository.findById(999L)).thenReturn(Optional.empty())

        val ex = assertFailsWith<NotFoundException> { writer.cancel(999L) }

        assertEquals(mapOf("reservationId" to 999L), ex.details)
        verify(eventRepository, never()).releaseReserved(any(), any())
    }

    @Test
    fun `should release capacity when expire update affects one row`() {
        whenever(reservationRepository.markExpired(5L)).thenReturn(1)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.EXPIRED)))

        val reservation = writer.expire(5L)

        assertEquals(ReservationStatus.EXPIRED, reservation.status)
        verify(eventRepository).releaseReserved(1L, 2)
    }

    @Test
    fun `should not release capacity when reservation was already expired`() {
        whenever(reservationRepository.markExpired(5L)).thenReturn(0)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.EXPIRED)))

        val reservation = writer.expire(5L)

        assertEquals(ReservationStatus.EXPIRED, reservation.status)
        verify(eventRepository, never()).releaseReserved(any(), any())
    }

    @Test
    fun `should not release capacity when reservation was cancelled instead of expired`() {
        whenever(reservationRepository.markExpired(5L)).thenReturn(0)
        whenever(reservationRepository.findById(5L))
            .thenReturn(Optional.of(reservationWith(ReservationStatus.CANCELLED)))

        val reservation = writer.expire(5L)

        assertEquals(ReservationStatus.CANCELLED, reservation.status)
        verify(eventRepository, never()).releaseReserved(any(), any())
    }
}
