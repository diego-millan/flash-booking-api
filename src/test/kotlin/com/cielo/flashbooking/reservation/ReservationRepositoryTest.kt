package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.event.Event
import com.cielo.flashbooking.event.EventRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReservationRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    private val expiresAt = Instant.now().truncatedTo(ChronoUnit.MICROS).plus(10, ChronoUnit.MINUTES)

    private fun newEvent(capacity: Int = 10): Event {
        val event = eventRepository.save(Event(name = "Rock Show", capacity = capacity))
        entityManager.flush()
        entityManager.clear()
        return event
    }

    @Test
    fun `should persist reservation when save`() {
        val event = newEvent()

        val saved = reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 2, idempotencyKey = "key-1", expiresAt = expiresAt),
        )
        entityManager.clear()

        val found = reservationRepository.findById(saved.id!!).orElseThrow()

        assertEquals(event.id, found.eventId)
        assertEquals(2, found.quantity)
        assertEquals(ReservationStatus.PENDING, found.status)
        assertEquals("key-1", found.idempotencyKey)
        assertEquals(expiresAt, found.expiresAt)
        assertTrue(found.createdAt.isBefore(Instant.now().plusSeconds(1)))
    }

    @Test
    fun `should find reservation when idempotency key exists`() {
        val event = newEvent()
        reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 3, idempotencyKey = "key-2", expiresAt = expiresAt),
        )

        val found = reservationRepository.findByIdempotencyKey("key-2")

        assertEquals(3, found?.quantity)
    }

    @Test
    fun `should return null when idempotency key does not exist`() {
        val found = reservationRepository.findByIdempotencyKey("unknown")

        assertEquals(null, found)
    }

    @Test
    fun `should reject insert when quantity is zero at database level`() {
        val event = newEvent()

        assertFailsWith<DataIntegrityViolationException> {
            reservationRepository.saveAndFlush(
                Reservation(eventId = event.id!!, quantity = 0, idempotencyKey = "key-3", expiresAt = expiresAt),
            )
        }
    }

    @Test
    fun `should reject insert when idempotency key is duplicated at database level`() {
        val event = newEvent()
        reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 1, idempotencyKey = "key-4", expiresAt = expiresAt),
        )

        assertFailsWith<DataIntegrityViolationException> {
            reservationRepository.saveAndFlush(
                Reservation(eventId = event.id!!, quantity = 2, idempotencyKey = "key-4", expiresAt = expiresAt),
            )
        }
    }

    @Test
    fun `should add reserved when event has availability`() {
        val event = newEvent(capacity = 10)

        val updatedRows = eventRepository.addReserved(event.id!!, 4)

        assertEquals(1, updatedRows)
        val found = eventRepository.findById(event.id!!).orElseThrow()
        assertEquals(4, found.reserved)
    }

    @Test
    fun `should keep reserved unchanged when requested quantity exceeds capacity`() {
        val event = newEvent(capacity = 10)
        eventRepository.addReserved(event.id!!, 4)

        val updatedRows = eventRepository.addReserved(event.id!!, 7)

        assertEquals(0, updatedRows)
        val found = eventRepository.findById(event.id!!).orElseThrow()
        assertEquals(4, found.reserved)
        assertTrue(found.reserved <= found.capacity)
    }

    @Test
    fun `should reject reserved above capacity at database level`() {
        val event = newEvent(capacity = 10)
        entityManager.persist(
            Reservation(eventId = event.id!!, quantity = 1, idempotencyKey = "key-6", expiresAt = expiresAt),
        )
        entityManager.flush()

        val found = eventRepository.findById(event.id!!).orElseThrow()
        found.reserved = 11

        assertFailsWith<DataIntegrityViolationException> { eventRepository.saveAndFlush(found) }
    }

    @Test
    fun `should cancel reservation when it is pending`() {
        val event = newEvent()
        val saved = reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 2, idempotencyKey = "key-cancel", expiresAt = expiresAt),
        )

        val updatedRows = reservationRepository.markCancelled(saved.id!!)

        assertEquals(1, updatedRows)
        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(saved.id!!).orElseThrow().status)
    }

    @Test
    fun `should keep reservation unchanged when it is already expired`() {
        val event = newEvent()
        val saved = reservationRepository.saveAndFlush(
            Reservation(
                eventId = event.id!!,
                quantity = 2,
                status = ReservationStatus.EXPIRED,
                idempotencyKey = "key-expired",
                expiresAt = expiresAt,
            ),
        )

        val updatedRows = reservationRepository.markCancelled(saved.id!!)

        assertEquals(0, updatedRows)
        assertEquals(ReservationStatus.EXPIRED, reservationRepository.findById(saved.id!!).orElseThrow().status)
    }

    @Test
    fun `should release reserved when event has reservation`() {
        val event = newEvent(capacity = 10)
        eventRepository.addReserved(event.id!!, 4)

        val updatedRows = eventRepository.releaseReserved(event.id!!, 4)

        assertEquals(1, updatedRows)
        val found = eventRepository.findById(event.id!!).orElseThrow()
        assertEquals(0, found.reserved)
        assertTrue(found.reserved <= found.capacity)
    }

    @Test
    fun `should keep reserved unchanged when release would make it negative`() {
        val event = newEvent(capacity = 10)

        val updatedRows = eventRepository.releaseReserved(event.id!!, 4)

        assertEquals(0, updatedRows)
        assertEquals(0, eventRepository.findById(event.id!!).orElseThrow().reserved)
    }

    @Test
    fun `should expire reservation when it is pending`() {
        val event = newEvent()
        val saved = reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 2, idempotencyKey = "key-expire", expiresAt = expiresAt),
        )

        val updatedRows = reservationRepository.markExpired(saved.id!!)

        assertEquals(1, updatedRows)
        assertEquals(ReservationStatus.EXPIRED, reservationRepository.findById(saved.id!!).orElseThrow().status)
    }

    @Test
    fun `should keep reservation unchanged when it is already cancelled and expire is attempted`() {
        val event = newEvent()
        val saved = reservationRepository.saveAndFlush(
            Reservation(
                eventId = event.id!!,
                quantity = 2,
                status = ReservationStatus.CANCELLED,
                idempotencyKey = "key-cancelled",
                expiresAt = expiresAt,
            ),
        )

        val updatedRows = reservationRepository.markExpired(saved.id!!)

        assertEquals(0, updatedRows)
        assertEquals(ReservationStatus.CANCELLED, reservationRepository.findById(saved.id!!).orElseThrow().status)
    }

    @Test
    fun `should find only past due pending reservations when sweeping`() {
        val event = newEvent()
        val pastDue = reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 1, idempotencyKey = "sweep-past-due", expiresAt = expiresAt.minus(1, ChronoUnit.HOURS)),
        )
        reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 1, idempotencyKey = "sweep-future", expiresAt = expiresAt),
        )
        reservationRepository.saveAndFlush(
            Reservation(
                eventId = event.id!!,
                quantity = 1,
                status = ReservationStatus.CANCELLED,
                idempotencyKey = "sweep-cancelled",
                expiresAt = expiresAt.minus(1, ChronoUnit.HOURS),
            ),
        )

        val ids = reservationRepository.findExpiredIds(ReservationStatus.PENDING, Instant.now())

        assertEquals(listOf(pastDue.id), ids)
    }
}
