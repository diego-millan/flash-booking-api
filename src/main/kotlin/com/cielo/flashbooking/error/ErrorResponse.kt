package com.cielo.flashbooking.error

data class ErrorResponse(val error: ApiError)

data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, Any> = emptyMap(),
)
