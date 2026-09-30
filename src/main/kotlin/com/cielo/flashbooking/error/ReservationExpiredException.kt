package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class ReservationExpiredException(reservationId: Long) :
    ApiException(
        status = HttpStatus.CONFLICT,
        code = "RESERVATION_EXPIRED",
        message = "Reservation already expired and its capacity was released",
        details = mapOf("reservationId" to reservationId),
    )
