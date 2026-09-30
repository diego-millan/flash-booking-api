package com.cielo.flashbooking.error

import com.cielo.flashbooking.event.EventController
import com.cielo.flashbooking.event.EventService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(EventController::class)
class ApiExceptionHandlerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var eventService: EventService

    @Test
    fun `should return 404 NOT_FOUND when path does not exist`() {
        mockMvc.get("/does-not-exist").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.details.path") { value("does-not-exist") }
        }
    }

    @Test
    fun `should return 405 METHOD_NOT_ALLOWED when method is not supported`() {
        mockMvc.get("/events").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.error.code") { value("METHOD_NOT_ALLOWED") }
        }
    }

    @Test
    fun `should return 500 INTERNAL_ERROR when exception is unexpected`() {
        whenever(eventService.create(any())).thenThrow(IllegalStateException("boom"))

        mockMvc.post("/events") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":10}"""
        }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.error.code") { value("INTERNAL_ERROR") }
        }
    }
}
