package com.cielo.flashbooking.event.dto

import com.cielo.flashbooking.event.EventStatus
import java.time.Instant

data class EventResponse(
    val id: Long,
    val name: String,
    val capacity: Int,
    val reserved: Int,
    val available: Int,
    val status: EventStatus,
    val createdAt: Instant,
)
