package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class PhasesDEIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `order saga reserves calculates tariff mock pays confirms and issues ticket`() {
        val departureTime = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, routeNumber = "101")
        waitForOutbox()

        val shiftId = openShift()

        val orderResponse = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 3,
                  "passengerName":"Anna Smirnova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PAID") }
            jsonPath("$.totalCents") { value(125000) }
            jsonPath("$.ticketNumber") { exists() }
            jsonPath("$.paymentTransactionId") { exists() }
        }.andReturn().response.contentAsString

        assertThat(orderResponse).doesNotContain("priceCents")
        waitForOutbox()
    }

    @Test
    fun `confirm and release reservation endpoints`() {
        val departureTime = Instant.now().plus(7, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, routeNumber = "202")
        waitForOutbox()

        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":4}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val reservationId = extractJsonField(reservationResponse, "id")

        mockMvc.post("/api/reservations/$reservationId/confirm") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CONSUMED") }
        }

        val reservationResponse2 = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":5}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val reservationId2 = extractJsonField(reservationResponse2, "id")

        mockMvc.post("/api/reservations/$reservationId2/release") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("EXPIRED") }
        }
    }

    @Test
    fun `refund ticket applies PP RF 112 tiers and releases seat`() {
        val departureTime = Instant.now().plus(30, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, routeNumber = "101")
        waitForOutbox()
        val shiftId = openShift()

        val orderResponse = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 6,
                  "passengerName":"Refund Test",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 654321",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val ticketId = extractJsonField(orderResponse, "ticketId")

        mockMvc.post("/api/tickets/$ticketId/refund") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.penaltyPercent") { value("0.00") }
            jsonPath("$.refundCents") { value(125000) }
        }

        waitForOutbox()
    }

    @Test
    fun `session cannot hold more than ten active reservations`() {
        val departureTime = Instant.now().plus(8, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, routeNumber = "202", seatCount = 45)
        waitForOutbox()

        repeat(10) { seat ->
            mockMvc.post("/api/reservations") {
                header(HttpHeaders.AUTHORIZATION, authHeader)
                contentType = MediaType.APPLICATION_JSON
                content = """{"tripId":"$tripId","seatNumber":${seat + 1}}"""
            }.andExpect { status { isOk() } }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":11}"""
        }.andExpect { status { isConflict() } }
    }

    private fun createTrip(departureTime: Instant, routeNumber: String, seatCount: Int = 20): String {
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "$routeNumber",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "3",
                  "seatCount": $seatCount
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openShift(): String {
        val response = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
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

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }
}
