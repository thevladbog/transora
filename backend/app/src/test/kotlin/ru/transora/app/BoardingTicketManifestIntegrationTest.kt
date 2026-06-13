package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.transora.app.dev.DevStationIds
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardingTicketManifestIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var adminAuth: String
    private lateinit var agentAuth: String

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        agentAuth = TestAuth.bearer(
            TestAuth.login(
                mockMvc,
                "station_agent",
                "station_agent",
                DevStationIds.TERMINAL_1.toString(),
            ),
        )
    }

    @Test
    fun `station agent can fetch ticket manifest for trip`() {
        val departure = Instant.now().plus(4, ChronoUnit.HOURS).toString()
        val tripJson = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "903",
                  "departureStation": "Bus Station Terminal 1",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departure",
                  "platform": "2",
                  "seatCount": 40
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val tripId = extractJsonField(tripJson, "id")
        waitForOutbox()

        val shiftJson = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"T1","cashierName":"manifest-cashier","posId":"POS-MAN"}"""
        }.andReturn().response.contentAsString
        val shiftId = extractJsonField(shiftJson, "id")

        val orderJson = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId": "$shiftId",
                  "tripId": "$tripId",
                  "seatNumber": 9,
                  "passengerName": "Manifest Passenger",
                  "docType": "PASSPORT_RF",
                  "docNumber": "4510 888888",
                  "paymentType": "CASH"
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val ticketId = extractJsonField(orderJson, "ticketId")

        val manifest = mockMvc.get("/api/boarding/trips/$tripId/tickets") {
            header(HttpHeaders.AUTHORIZATION, agentAuth)
        }.andReturn().response.contentAsString

        assertThat(manifest).contains("\"tripId\":\"$tripId\"")
        assertThat(manifest).contains("\"ticketId\":\"$ticketId\"")
        assertThat(manifest).contains("\"status\":\"ISSUED\"")
        assertThat(manifest).contains("Manifest Passenger")
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }

    private fun waitForOutbox() {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15)
        while (System.currentTimeMillis() < deadline) {
            if (outboxEventRepository.countUnpublished() == 0) {
                return
            }
            outboxPublisher.publishPendingEvents()
            Thread.sleep(200)
        }
        throw AssertionError("Outbox events were not published in time")
    }
}
