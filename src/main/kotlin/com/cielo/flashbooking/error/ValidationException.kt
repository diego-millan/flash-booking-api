package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class ValidationException(message: String, details: Map<String, Any> = emptyMap()) :
    ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, details)
