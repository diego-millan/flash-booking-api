package com.cielo.flashbooking.event

import com.cielo.flashbooking.error.ErrorResponse
import com.cielo.flashbooking.event.dto.CreateEventRequest
import com.cielo.flashbooking.event.dto.EventResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
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
    @Operation(
        summary = "Create an event",
        description = "Creates an event with a fixed seat capacity. Capacity must be greater than zero.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Event created (reserved starts at 0)",
                content = [Content(schema = Schema(implementation = EventResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR — missing, malformed or wrongly typed field",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "415",
                description = "UNSUPPORTED_MEDIA_TYPE — request content type is not JSON",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "422",
                description = "INVALID_QUANTITY — capacity is zero or negative",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    fun create(@Valid @RequestBody request: CreateEventRequest): EventResponse = eventService.create(request)

    @GetMapping("/{id}")
    @Operation(
        summary = "Get event availability",
        description = "Returns the event with its capacity, reserved seats and availability.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Event found",
                content = [Content(schema = Schema(implementation = EventResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR — id is not a number",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "NOT_FOUND — event does not exist",
                content = [Content(schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    fun get(
        @Parameter(description = "Event id", example = "38") @PathVariable id: Long,
    ): EventResponse = eventService.get(id)
}
