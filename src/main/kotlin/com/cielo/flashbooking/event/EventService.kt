package com.cielo.flashbooking.event

import com.cielo.flashbooking.event.dto.CreateEventRequest
import com.cielo.flashbooking.event.dto.EventResponse
import org.springframework.stereotype.Service

@Service
class EventService(private val eventRepository: EventRepository) {

    fun create(request: CreateEventRequest): EventResponse {
        val event = Event(name = request.name.trim(), capacity = request.capacity)
        return eventRepository.save(event).toResponse()
    }
}

internal fun Event.toResponse() = EventResponse(
    id = requireNotNull(id),
    name = name,
    capacity = capacity,
    reserved = reserved,
    available = capacity - reserved,
)
