package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class InvalidQuantityException(field: String, value: Int) :
    ApiException(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        code = "INVALID_QUANTITY",
        message = "Quantity must be greater than zero",
        details = mapOf(field to value),
    )
