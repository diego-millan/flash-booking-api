package com.cielo.flashbooking.event

import com.cielo.flashbooking.event.dto.CreateEventRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class EventServiceTest {

    private val eventRepository = mock<EventRepository>()
    private val eventService = EventService(eventRepository)

    @Test
    fun `should return created event with zero reserved when create with valid request`() {
        whenever(eventRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Event).apply { id = 1L }
        }

        val response = eventService.create(CreateEventRequest(name = "Rock Show", capacity = 100))

        assertEquals(1L, response.id)
        assertEquals("Rock Show", response.name)
        assertEquals(100, response.capacity)
        assertEquals(0, response.reserved)
        assertEquals(100, response.available)
    }

    @Test
    fun `should trim event name when create with surrounding whitespace`() {
        whenever(eventRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Event).apply { id = 1L }
        }

        val response = eventService.create(CreateEventRequest(name = "  Rock Show  ", capacity = 50))

        assertEquals("Rock Show", response.name)
    }

    @Test
    fun `should persist event with requested capacity when create`() {
        whenever(eventRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Event).apply { id = 7L }
        }

        eventService.create(CreateEventRequest(name = "Jazz Night", capacity = 30))

        val saved = org.mockito.kotlin.argumentCaptor<Event>()
        org.mockito.kotlin.verify(eventRepository).save(saved.capture())
        assertEquals("Jazz Night", saved.firstValue.name)
        assertEquals(30, saved.firstValue.capacity)
        assertEquals(0, saved.firstValue.reserved)
    }
}
