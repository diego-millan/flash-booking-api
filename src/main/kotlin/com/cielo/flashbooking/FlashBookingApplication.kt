package com.cielo.flashbooking

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class FlashBookingApplication

fun main(args: Array<String>) {
    runApplication<FlashBookingApplication>(*args)
}
