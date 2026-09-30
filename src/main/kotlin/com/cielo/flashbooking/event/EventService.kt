package com.cielo.flashbooking.event

import com.cielo.flashbooking.error.InvalidQuantityException
import com.cielo.flashbooking.error.NotFoundException
import com.cielo.flashbooking.event.dto.CreateEventRequest
import com.cielo.flashbooking.event.dto.EventResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class EventService(private val eventRepository: EventRepository) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun create(request: CreateEventRequest): EventResponse {
        if (request.capacity <= 0) {
            throw InvalidQuantityException("capacity", request.capacity)
        }
        val event = eventRepository.save(Event(name = request.name.trim(), capacity = request.capacity))
        logger.info("event created eventId={} capacity={} name={}", event.id, event.capacity, event.name)
        return event.toResponse()
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
