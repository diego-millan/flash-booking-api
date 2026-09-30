package com.cielo.flashbooking.error

import org.springframework.http.HttpStatus

class NotFoundException(idField: String, id: Any) :
    ApiException(
        status = HttpStatus.NOT_FOUND,
        code = "NOT_FOUND",
        message = "Requested resource does not exist",
        details = mapOf(idField to id),
    )
