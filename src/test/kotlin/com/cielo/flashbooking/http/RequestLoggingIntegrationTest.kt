package com.cielo.flashbooking.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequestLoggingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun attachAppender() {
        appender.list.clear()
        rootLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        rootLogger.detachAppender(appender)
    }

    private fun messages() = appender.list.map { it.formattedMessage }

    private fun createEvent(capacity: Int): Int {
        val body = mockMvc.post("/events") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Log Demo","capacity":$capacity}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        return JsonPath.read(body, "$.id")
    }

    @Test
    fun `should log method path status and duration when request succeeds`() {
        createEvent(capacity = 10)

        val line = messages().first { it.startsWith("request method=POST path=/events ") }
        assertTrue(line.contains("status=201"), line)
        assertTrue(line.contains("durationMs="), line)
    }

    @Test
    fun `should log warn with error code and path when request fails`() {
        mockMvc.get("/events/999999").andExpect { status { isNotFound() } }

        val warn = appender.list.first {
            it.level == Level.WARN && it.formattedMessage.contains("code=NOT_FOUND")
        }
        assertTrue(warn.formattedMessage.contains("path=/events/999999"), warn.formattedMessage)
    }

    @Test
    fun `should not log actuator endpoints when healthcheck polls`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }

        assertTrue(messages().none { it.contains("path=/actuator") })
    }

    @Test
    fun `should log created ids when event and reservation are created`() {
        val eventId = createEvent(capacity = 10)
        mockMvc.post("/events/$eventId/reservations") {
            header("Idempotency-Key", "log-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":2}"""
        }.andExpect { status { isCreated() } }

        assertTrue(messages().any { it.contains("event created eventId=$eventId") }, messages().joinToString("\n"))
        assertTrue(
            messages().any { it.startsWith("reservation created reservationId=") && it.contains("eventId=$eventId") },
            messages().joinToString("\n"),
        )
    }

    @Test
    fun `should log reservation id when reservation is cancelled`() {
        val eventId = createEvent(capacity = 10)
        val body = mockMvc.post("/events/$eventId/reservations") {
            header("Idempotency-Key", "log-cancel-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"quantity":1}"""
        }.andExpect { status { isCreated() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val reservationId: Int = JsonPath.read(body, "$.id")

        mockMvc.delete("/reservations/$reservationId").andExpect { status { isOk() } }

        assertTrue(
            messages().any { it.contains("reservation cancelled reservationId=$reservationId") },
            messages().joinToString("\n"),
        )
    }
}
