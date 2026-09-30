package com.cielo.flashbooking.event

import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.event.dto.CreateEventRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventServiceTest {

    private val eventRepository = mock<EventRepository>()
    private val eventService = EventService(eventRepository)

    private fun stubSave(id: Long = 1L) {
        whenever(eventRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Event).apply { this.id = id }
        }
    }

    @Test
    fun `should return created event with zero reserved when create with valid request`() {
        stubSave()

        val response = eventService.create(CreateEventRequest(name = "Rock Show", capacity = 100))

        assertEquals(1L, response.id)
        assertEquals("Rock Show", response.name)
        assertEquals(100, response.capacity)
        assertEquals(0, response.reserved)
        assertEquals(100, response.available)
        assertEquals(EventStatus.ACTIVE, response.status)
    }

    @Test
    fun `should trim event name when create with surrounding whitespace`() {
        stubSave()

        val response = eventService.create(CreateEventRequest(name = "  Rock Show  ", capacity = 50))

        assertEquals("Rock Show", response.name)
    }

    @Test
    fun `should persist event with requested capacity when create`() {
        stubSave(id = 7L)

        eventService.create(CreateEventRequest(name = "Jazz Night", capacity = 30))

        val saved = argumentCaptor<Event>()
        verify(eventRepository).save(saved.capture())
        assertEquals("Jazz Night", saved.firstValue.name)
        assertEquals(30, saved.firstValue.capacity)
        assertEquals(0, saved.firstValue.reserved)
        assertEquals(EventStatus.ACTIVE, saved.firstValue.status)
    }

    @Test
    fun `should set creation timestamp when create`() {
        stubSave()

        val response = eventService.create(CreateEventRequest(name = "Jazz Night", capacity = 30))

        assertTrue(response.createdAt.isBefore(java.time.Instant.now().plusSeconds(1)))
    }

    @Test
    fun `should throw invalid quantity when capacity is zero`() {
        val ex = assertFailsWith<InvalidQuantityException> {
            eventService.create(CreateEventRequest(name = "Rock Show", capacity = 0))
        }

        assertEquals("INVALID_QUANTITY", ex.code)
        assertEquals(422, ex.status.value())
        assertEquals(mapOf("capacity" to 0), ex.details)
    }

    @Test
    fun `should throw invalid quantity when capacity is negative`() {
        assertFailsWith<InvalidQuantityException> {
            eventService.create(CreateEventRequest(name = "Rock Show", capacity = -1))
        }
    }
}
