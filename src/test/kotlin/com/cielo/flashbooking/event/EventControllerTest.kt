package com.cielo.flashbooking.event

import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.event.dto.EventResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant

@WebMvcTest(EventController::class)
class EventControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var eventService: EventService

    private val createdAt = Instant.parse("2026-09-29T20:00:00Z")

    @Test
    fun `should return 201 with created event when request is valid`() {
        whenever(eventService.create(any())).thenReturn(
            EventResponse(1L, "Rock Show", 100, 0, 100, EventStatus.ACTIVE, createdAt),
        )

        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":100}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.name") { value("Rock Show") }
            jsonPath("$.capacity") { value(100) }
            jsonPath("$.reserved") { value(0) }
            jsonPath("$.available") { value(100) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { value("2026-09-29T20:00:00Z") }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when name is blank`() {
        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"   ","capacity":100}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
            jsonPath("$.error.details.name") { exists() }
        }
    }

    @Test
    fun `should return 422 INVALID_QUANTITY when capacity is zero`() {
        whenever(eventService.create(any())).thenThrow(InvalidQuantityException("capacity", 0))

        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":0}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("INVALID_QUANTITY") }
            jsonPath("$.error.details.capacity") { value(0) }
        }
    }

    @Test
    fun `should return 422 INVALID_QUANTITY when capacity is negative`() {
        whenever(eventService.create(any())).thenThrow(InvalidQuantityException("capacity", -10))

        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":-10}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error.code") { value("INVALID_QUANTITY") }
            jsonPath("$.error.details.capacity") { value(-10) }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when capacity is missing`() {
        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when body is malformed`() {
        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "Rock Show", """
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `should return 415 UNSUPPORTED_MEDIA_TYPE when content type is not json`() {
        mockMvc.post("/events") {
            contentType = MediaType.TEXT_PLAIN
            content = "name=Rock Show"
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.error.code") { value("UNSUPPORTED_MEDIA_TYPE") }
        }
    }

    @Test
    fun `should return 200 with event when it exists`() {
        whenever(eventService.get(1L)).thenReturn(
            EventResponse(1L, "Rock Show", 100, 30, 70, EventStatus.ACTIVE, createdAt),
        )

        mockMvc.get("/events/1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.name") { value("Rock Show") }
            jsonPath("$.capacity") { value(100) }
            jsonPath("$.reserved") { value(30) }
            jsonPath("$.available") { value(70) }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.createdAt") { value("2026-09-29T20:00:00Z") }
        }
    }

    @Test
    fun `should return 404 NOT_FOUND when event does not exist`() {
        whenever(eventService.get(999L)).thenThrow(NotFoundException("eventId", 999L))

        mockMvc.get("/events/999").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.details.eventId") { value(999) }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when event id is not a number`() {
        mockMvc.get("/events/abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
            jsonPath("$.error.details.id") { exists() }
        }
    }
}
