package com.cielo.flashbooking.event

import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.event.dto.CreateEventRequest
import com.cielo.flashbooking.event.dto.EventResponse
import org.springframework.stereotype.Service

@Service
class EventService(private val eventRepository: EventRepository) {

    fun create(request: CreateEventRequest): EventResponse {
        if (request.capacity <= 0) {
            throw InvalidQuantityException("capacity", request.capacity)
        }
        val event = Event(name = request.name.trim(), capacity = request.capacity)
        return eventRepository.save(event).toResponse()
    }

    fun get(id: Long): EventResponse =
        eventRepository.findById(id).orElseThrow { NotFoundException("eventId", id) }.toResponse()
}

internal fun Event.toResponse() = EventResponse(
    id = requireNotNull(id),
    name = name,
    capacity = capacity,
    reserved = reserved,
    available = capacity - reserved,
    status = status,
    createdAt = createdAt,
)
