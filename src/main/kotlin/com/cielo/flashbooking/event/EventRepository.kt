package com.cielo.flashbooking.event

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface EventRepository : JpaRepository<Event, Long> {

    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE Event e SET e.reserved = e.reserved + :quantity " +
            "WHERE e.id = :eventId AND e.reserved + :quantity <= e.capacity",
    )
    fun addReserved(@Param("eventId") eventId: Long, @Param("quantity") quantity: Int): Int
}
