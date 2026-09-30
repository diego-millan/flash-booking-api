package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.error.CapacityExceededException
import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.reservation.dto.CreateReservationResult
import com.cielo.flashbooking.reservation.dto.ReservationResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(ReservationController::class)
class ReservationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var reservationService: ReservationService

    private val expiresAt = Instant.parse("2026-09-30T21:00:00Z")
    private val createdAt = Instant.parse("2026-09-30T20:50:00Z")

    private fun response(id: Long = 5L, eventId: Long = 1L, quantity: Int = 2) =
        ReservationResponse(id, eventId, quantity, ReservationStatus.PENDING, expiresAt, createdAt)

    @Test
    fun `should return 201 with reservation when request is valid`() {
        whenever(reservationService.create(eq(1L), any(), eq("idem-key-1")))
            .thenReturn(CreateReservationResult(response(), replayed = false))

        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(5) }
            jsonPath("$.eventId") { value(1) }
            jsonPath("$.quantity") { value(2) }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.expiresAt") { value("2026-09-30T21:00:00Z") }
            jsonPath("$.createdAt") { value("2026-09-30T20:50:00Z") }
        }
    }

    @Test
    fun `should return 200 with same reservation when idempotency key is repeated`() {
        whenever(reservationService.create(eq(1L), any(), eq("idem-key-1")))
            .thenReturn(CreateReservationResult(response(), replayed = true))

        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(5) }
            jsonPath("$.quantity") { value(2) }
        }
    }

    @Test
    fun `should return 404 NOT_FOUND when event does not exist`() {
        whenever(reservationService.create(eq(999L), any(), eq("idem-key-1")))
            .thenThrow(NotFoundException("eventId", 999L))

        mockMvc.post("/events/999/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.details.eventId") { value(999) }
        }
    }

    @Test
    fun `should return 409 CAPACITY_EXCEEDED when event has no availability`() {
        whenever(reservationService.create(eq(1L), any(), eq("idem-key-1")))
            .thenThrow(CapacityExceededException(1L, 5, 0))

        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":5}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error.code") { value("CAPACITY_EXCEEDED") }
            jsonPath("$.error.details.eventId") { value(1) }
            jsonPath("$.error.details.quantity") { value(5) }
            jsonPath("$.error.details.available") { value(0) }
        }
    }

    @Test
    fun `should return 422 INVALID_QUANTITY when quantity is zero`() {
        whenever(reservationService.create(eq(1L), any(), eq("idem-key-1")))
            .thenThrow(InvalidQuantityException("quantity", 0))

        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":0}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("INVALID_QUANTITY") }
            jsonPath("$.error.details.quantity") { value(0) }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when quantity is missing`() {
        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when idempotency key header is missing`() {
        mockMvc.post("/events/1/reservations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
            jsonPath("$.error.details.Idempotency-Key") { value("header is required") }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when body is malformed`() {
        mockMvc.post("/events/1/reservations") {
            header("Idempotency-Key", "idem-key-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":"""" + """}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        }
    }
}
