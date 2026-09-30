package com.cielo.flashbooking.event.dto

import com.cielo.flashbooking.event.EventStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "An event with its seat capacity and current occupancy")
data class EventResponse(
    @Schema(description = "Event id", example = "38")
    val id: Long,
    @Schema(description = "Event name", example = "Rock Show")
    val name: String,
    @Schema(description = "Total seats", example = "100")
    val capacity: Int,
    @Schema(description = "Seats currently held by PENDING reservations", example = "2")
    val reserved: Int,
    @Schema(description = "`capacity - reserved`", example = "98")
    val available: Int,
    @Schema(description = "ACTIVE or SOLD_OUT", example = "ACTIVE")
    val status: EventStatus,
    @Schema(description = "Creation instant", example = "2026-09-30T13:52:48Z")
    val createdAt: Instant,
)
