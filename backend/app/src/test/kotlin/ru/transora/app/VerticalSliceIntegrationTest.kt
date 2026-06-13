package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import ru.transora.app.test.TestIamConfiguration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class VerticalSliceIntegrationTest : IntegrationTestBase() {
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
    fun `unauthenticated ticket issue returns unauthorized`() {
        mockMvc.post("/api/tickets") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"reservationId":"00000000-0000-0000-0000-000000000001","shiftId":"00000000-0000-0000-0000-000000000002","passengerName":"Test","priceCents":100}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `happy path trip reserve shift ticket document`() {
        val departureTime = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripJson = """
            {
              "routeNumber": "101",
              "departureStation": "Transora Central",
              "arrivalStation": "North Terminal",
              "departureStationCode": "T1",
              "departureTime": "$departureTime",
              "platform": "3",
              "seatCount": 45
            }
        """.trimIndent()

        val tripResponse = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = tripJson
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val tripId = extractJsonField(tripResponse, "id")
        waitForOutbox()

        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":12}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val reservationId = extractJsonField(reservationResponse, "id")

        val shiftResponse = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val shiftId = extractJsonField(shiftResponse, "id")

        val ticketResponse = mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$shiftId",
                  "passengerName":"Ivan Petrov",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val ticketId = extractJsonField(ticketResponse, "id")
        assertThat(ticketResponse).contains("\"ticketNumber\"")

        waitForOutbox()

        mockMvc.get("/api/tickets/$ticketId/document") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            content { contentType("application/pdf") }
        }

        val boardBody = mockMvc.get("/api/board/departures?stationCode=T1&windowAfterMin=10080") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        assertThat(boardBody).contains("\"tripId\":\"$tripId\"")
        assertThat(boardBody).contains("\"route\":\"101\"")
    }

    @Test
    fun `issue ticket without open shift returns conflict`() {
        val departureTime = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime)
        val reservationId = createReservation(tripId, 5)
        mockMvc.post("/api/reservations/$reservationId/cancel") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
        val closedShiftId = openAndCloseShift()

        mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$closedShiftId",
                  "passengerName":"Ivan Petrov",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `issue ticket without reservation returns conflict`() {
        val shiftId = openShift()

        mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"00000000-0000-0000-0000-000000000099",
                  "shiftId":"$shiftId",
                  "passengerName":"Ivan Petrov",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `concurrent reservation allows only one winner`() {
        val departureTime = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime)
        val executor = Executors.newFixedThreadPool(10)

        try {
            val results = executor.invokeAll(
                (1..10).map {
                    Callable {
                        runCatching {
                            mockMvc.post("/api/reservations") {
                                header(HttpHeaders.AUTHORIZATION, authHeader)
                                contentType = MediaType.APPLICATION_JSON
                                content = """{"tripId":"$tripId","seatNumber":7}"""
                            }.andReturn().response.status
                        }
                    }
                },
            ).map { it.get() }

            assertThat(results.count { it.isSuccess && it.getOrNull() == 200 }).isEqualTo(1)
            assertThat(results.count { it.isFailure || it.getOrNull() != 200 }).isEqualTo(9)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancel reservation releases seat`() {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime)
        val reservationId = createReservation(tripId, 9)

        mockMvc.post("/api/reservations/$reservationId/cancel") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":9}"""
        }.andExpect { status { isOk() } }
    }

    private fun createTrip(departureTime: Instant): String {
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "202",
                  "departureStation": "Transora Central",
                  "arrivalStation": "South Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "1",
                  "seatCount": 20
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        waitForOutbox()
        return extractJsonField(response, "id")
    }

    private fun createReservation(tripId: String, seatNumber: Int): String {
        val response = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":$seatNumber}"""
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

    private fun openAndCloseShift(): String {
        val shiftId = openShift()
        mockMvc.post("/api/shifts/$shiftId/close") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
        return shiftId
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
