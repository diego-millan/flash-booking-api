package com.cielo.flashbooking.http

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RequestLoggingInterceptor : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        request.setAttribute(STARTED_AT, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        // the compose healthcheck polls /actuator/health every 5s on each replica
        if (request.requestURI.startsWith("/actuator")) return
        val startedAt = request.getAttribute(STARTED_AT) as? Long ?: return
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        logger.info(
            "request method={} path={} status={} durationMs={}",
            request.method,
            request.requestURI,
            response.status,
            durationMs,
        )
    }

    companion object {
        private const val STARTED_AT = "flashbooking.request.startedAt"
    }
}
