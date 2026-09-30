package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

abstract class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any> = emptyMap(),
) : RuntimeException(message)
