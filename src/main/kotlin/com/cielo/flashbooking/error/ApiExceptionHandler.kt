package com.cielo.flashbooking.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(ex: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logError(ex.status, ex.code, request, ex.details)
        return ResponseEntity.status(ex.status).body(ErrorResponse(ApiError(ex.code, ex.message, ex.details)))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        logError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, details)
        return ResponseEntity.badRequest().body(
            ErrorResponse(ApiError("VALIDATION_ERROR", "Request validation failed", details)),
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, emptyMap())
        return ResponseEntity.badRequest().body(
            ErrorResponse(ApiError("VALIDATION_ERROR", "Malformed or missing request body")),
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val details = mapOf(ex.name to (ex.value ?: "invalid"))
        logError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, details)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                ApiError(
                    "VALIDATION_ERROR",
                    "Request parameter has an invalid value",
                    details,
                ),
            ),
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val details = mapOf<String, Any>("path" to ex.resourcePath)
        logError(HttpStatus.NOT_FOUND, "NOT_FOUND", request, details)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(ApiError("NOT_FOUND", "Resource not found", details)),
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val details = mapOf<String, Any>("method" to (ex.method ?: "unknown"))
        logError(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", request, details)
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
            ErrorResponse(
                ApiError(
                    "METHOD_NOT_ALLOWED",
                    "Request method is not supported for this resource",
                    details,
                ),
            ),
        )
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(ex: HttpMediaTypeNotSupportedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", request, emptyMap())
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
            ErrorResponse(ApiError("UNSUPPORTED_MEDIA_TYPE", "Request content type is not supported")),
        )
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val details = mapOf<String, Any>(ex.headerName to "header is required")
        logError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request, details)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                ApiError(
                    "VALIDATION_ERROR",
                    "Required request header is missing",
                    details,
                ),
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        logError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request, emptyMap(), ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(ApiError("INTERNAL_ERROR", "Unexpected internal error")),
        )
    }

    private fun logError(
        status: HttpStatus,
        code: String,
        request: HttpServletRequest,
        details: Map<String, Any>,
        ex: Throwable? = null,
    ) {
        val message = "api error status={} code={} path={} details={}"
        if (status.is5xxServerError) {
            logger.error(message, status.value(), code, request.requestURI, details, ex)
        } else {
            logger.warn(message, status.value(), code, request.requestURI, details)
        }
    }
}
