package com.cielo.flashbooking.event.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreateEventRequest(
    @field:NotBlank
    val name: String,
    @field:Min(1)
    val capacity: Int,
)
