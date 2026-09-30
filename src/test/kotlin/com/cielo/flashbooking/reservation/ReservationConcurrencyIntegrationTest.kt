package com.cielo.flashbooking.reservation

import com.cielo.flashbooking.event.EventRepository
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReservationConcurrencyIntegrationTest {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @BeforeEach
    @AfterEach
    fun cleanDatabase() {
        reservationRepository.deleteAll()
        eventRepository.deleteAll()
    }

    @Test
    fun `should never oversell when 20 simultaneous requests compete for 5 seats`() {
        val eventId = createEvent(capacity = 5)

        val statuses = simultaneousReservations(eventId, quantity = 1, total = 20)

        assertTrue(statuses.all { it == 201 || it == 409 }, "unexpected statuses: $statuses")
        assertEquals(5, statuses.count { it == 201 })
        assertEquals(15, statuses.count { it == 409 })

        val (reserved, available) = eventSnapshot(eventId)
        assertEquals(5, reserved)
        assertEquals(0, available)
        assertEquals(5, reservationRepository.findAll().count { it.eventId == eventId.toLong() })
    }

    @Test
    fun `should admit every request that still fits when 20 simultaneous requests ask for 2 of 5 seats`() {
        val eventId = createEvent(capacity = 5)

        val statuses = simultaneousReservations(eventId, quantity = 2, total = 20)

        assertTrue(statuses.all { it == 201 || it == 409 }, "unexpected statuses: $statuses")
        assertEquals(2, statuses.count { it == 201 })
        assertEquals(18, statuses.count { it == 409 })

        val (reserved, available) = eventSnapshot(eventId)
        assertEquals(4, reserved)
        assertEquals(1, available)
    }

    private fun simultaneousReservations(eventId: Int, quantity: Int, total: Int): List<Int> {
        val executor = Executors.newFixedThreadPool(total)
        val startGate = CountDownLatch(1)
        val pending = (0 until total).map { index ->
            executor.submit<ResponseEntity<String>> {
                startGate.await()
                restTemplate.postForEntity(
                    "/events/$eventId/reservations",
                    reservationEntity(quantity, "conc-$eventId-$index"),
                    String::class.java,
                )
            }
        }

        startGate.countDown()
        val statuses = pending.map { it.get(60, TimeUnit.SECONDS).statusCodeValue }
        executor.shutdown()
        return statuses
    }

    private fun createEvent(capacity: Int): Int {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = restTemplate.postForEntity(
            "/events",
            HttpEntity("""{"name":"Rock Show","capacity":$capacity}""", headers),
            String::class.java,
        )

        assertEquals(201, response.statusCodeValue)
        return JsonPath.read(response.body!!, "$.id")
    }

    private fun reservationEntity(quantity: Int, key: String): HttpEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("Idempotency-Key", key)
        }
        return HttpEntity("""{"quantity":$quantity}""", headers)
    }

    private fun eventSnapshot(eventId: Int): Pair<Int, Int> {
        val body = restTemplate.getForObject("/events/$eventId", String::class.java)!!
        val reserved: Int = JsonPath.read(body, "$.reserved")
        val available: Int = JsonPath.read(body, "$.available")
        return reserved to available
    }
}
