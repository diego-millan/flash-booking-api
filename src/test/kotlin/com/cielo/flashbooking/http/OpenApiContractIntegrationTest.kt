package com.cielo.flashbooking.http

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private fun spec(): String =
        mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)

    @Test
    fun `should document every route when openapi is generated`() {
        val spec = spec()

        listOf("/events", "/events/{id}", "/events/{eventId}/reservations", "/reservations/{id}").forEach {
            assertTrue(spec.contains("\"$it\""), "route $it missing from the OpenAPI spec")
        }
    }

    @Test
    fun `should document the idempotency header as required when openapi is generated`() {
        val parameters: List<Map<String, Any>> = JsonPath.read(
            spec(),
            "$.paths['/events/{eventId}/reservations'].post.parameters[*]",
        )
        val header = parameters.first { it["name"] == "Idempotency-Key" }

        assertEquals("header", header["in"])
        assertEquals(true, header["required"])
    }

    @Test
    fun `should document every status code when openapi is generated`() {
        val spec = spec()

        val expected = mapOf(
            "POST /events" to listOf("201", "400", "415", "422"),
            "GET /events/{id}" to listOf("200", "400", "404"),
            "POST /events/{eventId}/reservations" to listOf("200", "201", "400", "404", "409", "415", "422"),
            "GET /reservations/{id}" to listOf("200", "400", "404"),
            "DELETE /reservations/{id}" to listOf("200", "400", "404", "409"),
        )
        expected.forEach { (route, codes) ->
            val (method, path) = route.split(" ")
            val responses: Map<String, Any> = JsonPath.read(spec, "$.paths['$path'].${method.lowercase()}.responses")
            codes.forEach { code ->
                assertTrue(code in responses, "status $code missing for $route (documented: ${responses.keys})")
            }
        }
    }

    @Test
    fun `should document the shared error envelope when openapi is generated`() {
        val spec = spec()

        val schemas: Map<String, Any> = JsonPath.read(spec, "$.components.schemas")
        assertTrue("ErrorResponse" in schemas, "ErrorResponse schema missing (documented: ${schemas.keys})")

        val envelope: Map<String, Any> = JsonPath.read(spec, "$.components.schemas.ErrorResponse.properties")
        assertEquals(setOf("error"), envelope.keys)

        val error: Map<String, Any> = JsonPath.read(spec, "$.components.schemas.ApiError.properties")
        assertEquals(setOf("code", "message", "details"), error.keys)
    }

    @Test
    fun `should serve swagger ui when requested`() {
        mockMvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
    }
}
