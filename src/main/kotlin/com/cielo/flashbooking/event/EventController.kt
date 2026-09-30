package com.cielo.flashbooking.event

import com.cielo.flashbooking.event.dto.CreateEventRequest
import com.cielo.flashbooking.event.dto.EventResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/events")
class EventController(private val eventService: EventService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateEventRequest): EventResponse = eventService.create(request)

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): EventResponse = eventService.get(id)
}
