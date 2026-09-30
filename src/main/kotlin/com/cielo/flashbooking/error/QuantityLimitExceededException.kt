package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class QuantityLimitExceededException(quantity: Int, limit: Int) :
    ApiException(
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        code = "INVALID_QUANTITY",
        message = "Quantity exceeds the limit allowed per reservation",
        details = mapOf("quantity" to quantity, "limit" to limit),
    )
