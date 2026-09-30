package com.cielo.flashbooking.event

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"])
class EventRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var eventRepository: EventRepository

    @Test
    fun `should persist event when save`() {
        val saved = eventRepository.save(Event(name = "Rock Show", capacity = 100))
        entityManager.flush()
        entityManager.clear()

        val found = eventRepository.findById(saved.id!!).orElseThrow()

        assertEquals("Rock Show", found.name)
        assertEquals(100, found.capacity)
        assertEquals(0, found.reserved)
        assertTrue(found.createdAt.isBefore(java.time.Instant.now().plusSeconds(1)))
    }

    @Test
    fun `should assign generated id when save`() {
        val saved = eventRepository.save(Event(name = "Jazz Night", capacity = 30))

        assertTrue(saved.id != null)
    }

    @Test
    fun `should return empty when find by unknown id`() {
        val found = eventRepository.findById(999999L)

        assertTrue(found.isEmpty)
    }
}
