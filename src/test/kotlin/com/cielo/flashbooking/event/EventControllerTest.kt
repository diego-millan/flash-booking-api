package com.cielo.flashbooking.event

import com.cielo.flashbooking.event.dto.EventResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(EventController::class)
class EventControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var eventService: EventService

    @Test
    fun `should return 201 with created event when request is valid`() {
        whenever(eventService.create(any())).thenReturn(EventResponse(1L, "Rock Show", 100, 0, 100))

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
    fun `should return 400 VALIDATION_ERROR when capacity is less than 1`() {
        mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
            jsonPath("$.error.details.capacity") { exists() }
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
}
