package com.cielo.flashbooking.event.dto

data class EventResponse(
    val id: Long,
    val name: String,
    val capacity: Int,
    val reserved: Int,
    val available: Int,
)
