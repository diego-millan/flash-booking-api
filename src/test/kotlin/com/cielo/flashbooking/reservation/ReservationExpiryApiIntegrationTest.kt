package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.event.EventRepository
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReservationExpiryApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationExpiryService: ReservationExpiryService

    private fun createEvent(capacity: Int): Int {
        val body = mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":$capacity}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        return JsonPath.read(body, "$.id")
    }

    private fun reserve(eventId: Int, quantity: Int, key: String): Long {
        val body = mockMvc.post("/events/$eventId/reservations") {
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":$quantity}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        return JsonPath.read<Number>(body, "$.id").toLong()
    }

    private fun makePastDue(reservationId: Long) {
        val reservation = reservationRepository.findById(reservationId).orElseThrow()
        reservation.expiresAt = Instant.now().minusSeconds(5)
        reservationRepository.saveAndFlush(reservation)
    }

    private fun assertAvailability(eventId: Int, reserved: Int, available: Int) {
        mockMvc.get("/events/$eventId").andExpect {
            status { isOk() }
            jsonPath("$.reserved") { value(reserved) }
            jsonPath("$.available") { value(available) }
        }
    }

    @Test
    fun `should expire reservation and release capacity when sweep runs`() {
        val eventId = createEvent(capacity = 10)
        val reservationId = reserve(eventId, 4, "key-sweep")
        makePastDue(reservationId)

        reservationExpiryService.sweep()

        mockMvc.get("/reservations/$reservationId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("EXPIRED") }
        }
        assertAvailability(eventId, reserved = 0, available = 10)
    }

    @Test
    fun `should release capacity only once when sweep runs twice`() {
        val eventId = createEvent(capacity = 10)
        val reservationId = reserve(eventId, 4, "key-sweep-twice")
        makePastDue(reservationId)

        reservationExpiryService.sweep()
        reservationExpiryService.sweep()

        assertAvailability(eventId, reserved = 0, available = 10)
        assertEquals(
            ReservationStatus.EXPIRED,
            reservationRepository.findById(reservationId).orElseThrow().status,
        )
    }

    @Test
    fun `should keep reservation pending when expiresAt is in the future`() {
        val eventId = createEvent(capacity = 10)
        val reservationId = reserve(eventId, 4, "key-not-due")

        reservationExpiryService.sweep()

        mockMvc.get("/reservations/$reservationId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PENDING") }
        }
        assertAvailability(eventId, reserved = 4, available = 6)
    }

    @Test
    fun `should expire reservation on demand when fetching past due reservation`() {
        val eventId = createEvent(capacity = 10)
        val reservationId = reserve(eventId, 4, "key-on-demand")
        makePastDue(reservationId)

        mockMvc.get("/reservations/$reservationId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("EXPIRED") }
        }
        assertAvailability(eventId, reserved = 0, available = 10)
    }

    @Test
    fun `should return 409 RESERVATION_EXPIRED and release capacity when cancelling past due reservation`() {
        val eventId = createEvent(capacity = 10)
        val reservationId = reserve(eventId, 4, "key-cancel-expired")
        makePastDue(reservationId)

        mockMvc.delete("/reservations/$reservationId").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RESERVATION_EXPIRED") }
            jsonPath("$.error.details.reservationId") { value(reservationId.toInt()) }
        }

        mockMvc.delete("/reservations/$reservationId").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("RESERVATION_EXPIRED") }
        }

        assertAvailability(eventId, reserved = 0, available = 10)
    }
}
