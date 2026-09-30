package com.cielo.flashbooking.event

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
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
    fun `should default to active status when create without status`() {
        val saved = eventRepository.save(Event(name = "Jazz Night", capacity = 30))
        entityManager.flush()
        entityManager.clear()

        val found = eventRepository.findById(saved.id!!).orElseThrow()

        assertEquals(EventStatus.ACTIVE, found.status)
    }

    @Test
    fun `should persist paused status when save with paused status`() {
        val saved = eventRepository.save(Event(name = "Jazz Night", capacity = 30, status = EventStatus.PAUSED))
        entityManager.flush()
        entityManager.clear()

        val found = eventRepository.findById(saved.id!!).orElseThrow()

        assertEquals(EventStatus.PAUSED, found.status)
    }

    @Test
    fun `should return empty when find by unknown id`() {
        val found = eventRepository.findById(999999L)

        assertTrue(found.isEmpty)
    }

    @Test
    fun `should reject insert when capacity is zero at database level`() {
        assertFailsWith<DataIntegrityViolationException> {
            eventRepository.saveAndFlush(Event(name = "Rock Show", capacity = 0))
        }
    }

    @Test
    fun `should reject update when reserved exceeds capacity at database level`() {
        val saved = eventRepository.saveAndFlush(Event(name = "Rock Show", capacity = 10))
        entityManager.clear()

        val found = eventRepository.findById(saved.id!!).orElseThrow()
        found.reserved = 11

        assertFailsWith<DataIntegrityViolationException> {
            eventRepository.saveAndFlush(found)
        }
    }
}
