package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class IdempotencyConflictException(eventId: Long, reservedEventId: Long) :
    ApiException(
        status = HttpStatus.CONFLICT,
        code = "IDEMPOTENCY_CONFLICT",
        message = "Idempotency-Key was already used for a different event",
        details = mapOf("eventId" to eventId, "reservedEventId" to reservedEventId),
    )
