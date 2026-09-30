package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.CapacityExceededException
import com.cielo.flashbooking.error.IdempotencyConflictException
import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.error.QuantityLimitExceededException
import com.cielo.flashbooking.error.ValidationException
import com.cielo.flashbooking.event.Event
import com.cielo.flashbooking.event.EventRepository
import com.cielo.flashbooking.reservation.dto.CreateReservationRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReservationServiceTest {

    private val eventRepository = mock<EventRepository>()
    private val reservationRepository = mock<ReservationRepository>()
    private val reservationWriter = mock<ReservationWriter>()
    private val service = ReservationService(
        eventRepository = eventRepository,
        reservationRepository = reservationRepository,
        reservationWriter = reservationWriter,
        maxQuantity = 10,
        ttlMinutes = 10,
    )

    private val key = "idem-key-1"

    private fun givenEvent(id: Long = 1L, capacity: Int = 100, reserved: Int = 0) {
        whenever(eventRepository.findById(id))
            .thenReturn(Optional.of(Event(id = id, name = "Rock Show", capacity = capacity, reserved = reserved)))
    }

    private fun writtenReservation(id: Long = 5L, eventId: Long = 1L, quantity: Int = 2) = Reservation(
        id = id,
        eventId = eventId,
        quantity = quantity,
        idempotencyKey = key,
        expiresAt = Instant.parse("2026-09-30T21:00:00Z"),
        createdAt = Instant.parse("2026-09-30T20:50:00Z"),
    )

    @Test
    fun `should create reservation when event has availability`() {
        givenEvent()
        whenever(reservationWriter.write(eq(1L), eq(2), eq(key), any())).thenReturn(writtenReservation())

        val result = service.create(1L, CreateReservationRequest(quantity = 2), key)

        assertEquals(false, result.replayed)
        assertEquals(5L, result.reservation.id)
        assertEquals(1L, result.reservation.eventId)
        assertEquals(2, result.reservation.quantity)
        assertEquals(ReservationStatus.PENDING, result.reservation.status)
        verify(reservationWriter).write(eq(1L), eq(2), eq(key), any())
    }

    @Test
    fun `should set expiration timestamp when ttl has passed`() {
        givenEvent()
        whenever(reservationWriter.write(eq(1L), eq(2), eq(key), any())).thenReturn(writtenReservation())

        service.create(1L, CreateReservationRequest(quantity = 2), key)

        val expiresAt = org.mockito.kotlin.argumentCaptor<Instant>()
        verify(reservationWriter).write(eq(1L), eq(2), eq(key), expiresAt.capture())
        val expected = Instant.now().plus(java.time.Duration.ofMinutes(10))
        assertTrue(expiresAt.firstValue.isAfter(expected.minusSeconds(60)))
        assertTrue(expiresAt.firstValue.isBefore(expected.plusSeconds(60)))
    }

    @Test
    fun `should return replayed reservation when idempotency key was already used`() {
        givenEvent()
        whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(writtenReservation())

        val result = service.create(1L, CreateReservationRequest(quantity = 2), key)

        assertEquals(true, result.replayed)
        assertEquals(5L, result.reservation.id)
        verify(reservationWriter, never()).write(any(), any(), any(), any())
    }

    @Test
    fun `should return replayed reservation when concurrent request wins the unique key`() {
        givenEvent()
        whenever(reservationRepository.findByIdempotencyKey(key))
            .thenReturn(null)
            .thenReturn(writtenReservation())
        whenever(reservationWriter.write(any(), any(), any(), any()))
            .thenThrow(DataIntegrityViolationException("duplicate key value"))

        val result = service.create(1L, CreateReservationRequest(quantity = 2), key)

        assertEquals(true, result.replayed)
        assertEquals(5L, result.reservation.id)
    }

    @Test
    fun `should rethrow data integrity error when reservation is not found after write failure`() {
        givenEvent()
        whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(null)
        whenever(reservationWriter.write(any(), any(), any(), any()))
            .thenThrow(DataIntegrityViolationException("check violation"))

        assertFailsWith<DataIntegrityViolationException> {
            service.create(1L, CreateReservationRequest(quantity = 2), key)
        }
    }

    @Test
    fun `should throw not found when event does not exist`() {
        whenever(eventRepository.findById(999L)).thenReturn(Optional.empty())

        val ex = assertFailsWith<NotFoundException> {
            service.create(999L, CreateReservationRequest(quantity = 2), key)
        }

        assertEquals(404, ex.status.value())
        assertEquals(mapOf("eventId" to 999L), ex.details)
    }

    @Test
    fun `should throw invalid quantity when quantity is zero`() {
        givenEvent()

        val ex = assertFailsWith<InvalidQuantityException> {
            service.create(1L, CreateReservationRequest(quantity = 0), key)
        }

        assertEquals("INVALID_QUANTITY", ex.code)
        assertEquals(mapOf("quantity" to 0), ex.details)
    }

    @Test
    fun `should throw quantity limit when quantity is greater than max`() {
        givenEvent()

        val ex = assertFailsWith<QuantityLimitExceededException> {
            service.create(1L, CreateReservationRequest(quantity = 11), key)
        }

        assertEquals("INVALID_QUANTITY", ex.code)
        assertEquals(422, ex.status.value())
        assertEquals(mapOf("quantity" to 11, "limit" to 10), ex.details)
    }

    @Test
    fun `should throw idempotency conflict when key was used for another event`() {
        givenEvent()
        whenever(reservationRepository.findByIdempotencyKey(key)).thenReturn(writtenReservation(eventId = 7L))

        val ex = assertFailsWith<IdempotencyConflictException> {
            service.create(1L, CreateReservationRequest(quantity = 2), key)
        }

        assertEquals("IDEMPOTENCY_CONFLICT", ex.code)
        assertEquals(409, ex.status.value())
    }

    @Test
    fun `should throw validation error when idempotency key is blank`() {
        givenEvent()

        val ex = assertFailsWith<ValidationException> {
            service.create(1L, CreateReservationRequest(quantity = 2), "   ")
        }

        assertEquals("VALIDATION_ERROR", ex.code)
        assertEquals(400, ex.status.value())
    }

    @Test
    fun `should throw capacity exceeded when writer cannot reserve capacity`() {
        givenEvent()
        whenever(reservationWriter.write(any(), any(), any(), any()))
            .thenThrow(CapacityExceededException(1L, 5, 0))

        val ex = assertFailsWith<CapacityExceededException> {
            service.create(1L, CreateReservationRequest(quantity = 5), key)
        }

        assertEquals("CAPACITY_EXCEEDED", ex.code)
        assertEquals(409, ex.status.value())
    }
}
