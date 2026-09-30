package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class CapacityExceededException(eventId: Long, quantity: Int, available: Int) :
    ApiException(
        status = HttpStatus.CONFLICT,
        code = "CAPACITY_EXCEEDED",
        message = "Event has no availability for the requested quantity",
        details = mapOf("eventId" to eventId, "quantity" to quantity, "available" to available),
    )
