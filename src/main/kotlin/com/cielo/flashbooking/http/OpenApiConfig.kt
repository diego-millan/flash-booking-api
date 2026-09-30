package com.cielo.flashbooking.http

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(
        title = "Flash Booking API",
        version = "0.0.1",
        description = "Reservation API for flash sales: events with a fixed seat capacity, reservations " +
            "limited per order, idempotent creation via the required `Idempotency-Key` header, " +
            "cancellation and TTL-based expiry with capacity release.\n\n" +
            "Every failure uses the same envelope: `{\"error\": {\"code\", \"message\", \"details\"}}`. " +
            "Codes: `VALIDATION_ERROR` (400), `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), " +
            "`CAPACITY_EXCEEDED` / `IDEMPOTENCY_CONFLICT` / `RESERVATION_EXPIRED` (409), " +
            "`UNSUPPORTED_MEDIA_TYPE` (415), `INVALID_QUANTITY` (422), `INTERNAL_ERROR` (500). " +
            "Unsupported methods (405) and unexpected failures (500) apply to every route.\n\n" +
            "Oversell is prevented by a conditional `UPDATE ... WHERE reserved + qty <= capacity` " +
            "plus the database constraint `CHECK (reserved <= capacity)`.",
    ),
)
class OpenApiConfig
