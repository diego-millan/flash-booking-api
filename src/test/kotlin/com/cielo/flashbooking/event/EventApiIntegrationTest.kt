package com.cielo.flashbooking.event

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EventApiIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `should return created event when fetch after create`() {
        val body = mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Rock Show","capacity":100}"""
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.getContentAsString(Charsets.UTF_8)

        val id: Int = JsonPath.read(body, "$.id")

        mockMvc.get("/events/$id").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(id) }
            jsonPath("$.name") { value("Rock Show") }
            jsonPath("$.capacity") { value(100) }
            jsonPath("$.reserved") { value(0) }
            jsonPath("$.available") { value(100) }
            jsonPath("$.status") { value("ACTIVE") }
        }
    }

    @Test
    fun `should return 404 NOT_FOUND when event does not exist`() {
        mockMvc.get("/events/999999").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value("NOT_FOUND") }
            jsonPath("$.error.message") { exists() }
            jsonPath("$.error.details.eventId") { value(999999) }
        }
    }

    @Test
    fun `should return 400 VALIDATION_ERROR when event id is not a number`() {
        mockMvc.get("/events/abc").andExpect {
            status { isBadRequest() }
            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
        }
    }
}
