package com.cielo.flashbooking.event.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Payload to create an event")
data class CreateEventRequest(
    @field:NotBlank
    @Schema(description = "Event name", example = "Rock Show", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @Schema(
        description = "Total seats. Must be greater than zero, otherwise `422 INVALID_QUANTITY`",
        example = "100",
        minimum = "1",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val capacity: Int,
)
