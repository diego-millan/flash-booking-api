package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.event.Event
import com.cielo.flashbooking.event.EventRepository
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReservationApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var reservationWriter: ReservationWriter

    private fun createEvent(capacity: Int): Int {
        val body = mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":$capacity}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)

        return JsonPath.read(body, "$.id")
    }

    private fun reserve(eventId: Int, quantity: Int, key: String) =
        mockMvc.post("/events/$eventId/reservations") {
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":$quantity}"""
        }

    @Test
    fun `should create reservation and reduce availability when request is valid`() {
        val eventId = createEvent(capacity = 100)

        reserve(eventId, 2, "key-ok").andExpect {
            status { isCreated() }
            jsonPath("$.eventId") { value(eventId) }
            jsonPath("$.quantity") { value(2) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.expiresAt") { exists() }
        }

        mockMvc.get("/events/$eventId").andExpect {
            status { isOk() }
            jsonPath("$.reserved") { value(2) }
            jsonPath("$.available") { value(98) }
        }
    }

    @Test
    fun `should return 409 CAPACITY_EXCEEDED and never oversell when event sells out`() {
        val eventId = createEvent(capacity = 3)

        repeat(3) { index ->
            reserve(eventId, 1, "key-sellout-$index").andExpect { status { isCreated() } }
        }

        reserve(eventId, 1, "key-sellout-overflow").andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("CAPACITY_EXCEEDED") }
            jsonPath("$.error.details.eventId") { value(eventId) }
            jsonPath("$.error.details.available") { value(0) }
        }

        mockMvc.get("/events/$eventId").andExpect {
            status { isOk() }
            jsonPath("$.reserved") { value(3) }
            jsonPath("$.capacity") { value(3) }
            jsonPath("$.available") { value(0) }
        }
    }

    @Test
    fun `should return 200 with same reservation and not double reserve when idempotency key is repeated`() {
        val eventId = createEvent(capacity = 10)

        val first = reserve(eventId, 4, "key-idem").andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val firstId: Int = JsonPath.read(first, "$.id")

        reserve(eventId, 4, "key-idem").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(firstId) }
            jsonPath("$.quantity") { value(4) }
        }

        mockMvc.get("/events/$eventId").andExpect {
            status { isOk() }
            jsonPath("$.reserved") { value(4) }
            jsonPath("$.available") { value(6) }
        }

        assertEquals(1, reservationRepository.findAll().count { it.eventId == eventId.toLong() })
    }

    @Test
    fun `should return 404 NOT_FOUND when event does not exist`() {
        reserve(999999, 1, "key-404").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.details.eventId") { value(999999) }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when idempotency key header is missing`() {
        mockMvc.post("/events/1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
            jsonPath("$.error.details.Idempotency-Key") { value("header is required") }
        }
    }

    @Test
    fun `should return 422 INVALID_QUANTITY when quantity exceeds the limit`() {
        val eventId = createEvent(capacity = 100)

        reserve(eventId, 11, "key-limit").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("INVALID_QUANTITY") }
            jsonPath("$.error.details.limit") { value(10) }
        }
    }

    @Test
    fun `should translate unique key violation when writer writes a repeated idempotency key`() {
        val event = eventRepository.save(Event(name = "Jazz Night", capacity = 10))
        val expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES)
        reservationRepository.saveAndFlush(
            Reservation(eventId = event.id!!, quantity = 1, idempotencyKey = "key-dupe", expiresAt = expiresAt),
        )

        val ex = assertFailsWith<DataIntegrityViolationException> {
            reservationWriter.write(event.id!!, 2, "key-dupe", expiresAt)
        }

        assertTrue(ex.message!!.contains("uq_reservations_idempotency_key"))
    }
}
