package com.cielo.flashbooking.event.dto

import jakarta.validation.constraints.NotBlank

data class CreateEventRequest(
    @field:NotBlank
    val name: String,
    val capacity: Int,
)
