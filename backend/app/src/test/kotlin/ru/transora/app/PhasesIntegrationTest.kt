package ru.transora.app

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class PhasesIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var adminAuth: String
    private lateinit var dispatcherAuth: String
    private lateinit var inspectorAuth: String

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
        inspectorAuth = TestAuth.bearer(TestAuth.login(mockMvc, "inspector", "inspector"))
    }

    @Test
    fun `dispatcher boarding and admin endpoints`() {
        val departureTime = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, adminAuth)
        waitForOutbox()
        val ticketId = sellTicket(tripId, adminAuth, seatNumber = 8)

        mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","allowedSeats":[1,2,3,4,5]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.stationId") { exists() }
        }

        mockMvc.post("/api/seat-blocks") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":20,"reason":"Maintenance"}"""
        }.andExpect { status { isOk() } }

        val announcementResponse = mockMvc.post("/api/announcements") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"textContent":"Boarding gate 3","priority":"HIGH"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val announcementId = extractJsonField(announcementResponse, "id")

        mockMvc.get("/api/announcements/queue") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.id == '$announcementId')].textContent") { value("Boarding gate 3") }
        }

        mockMvc.put("/api/announcements/$announcementId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"textContent":"Updated announcement"}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/boarding/scan") {
            header(HttpHeaders.AUTHORIZATION, inspectorAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticketId":"$ticketId","tripId":"$tripId"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.scanResult") { value("BOARDED") }
        }

        mockMvc.post("/api/boarding/scan") {
            header(HttpHeaders.AUTHORIZATION, inspectorAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ticketId":"$ticketId","tripId":"$tripId"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.scanResult") { value("ALREADY_USED") }
        }

        mockMvc.get("/api/trips/$tripId/boarding/stats") {
            header(HttpHeaders.AUTHORIZATION, inspectorAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.boardedCount") { value(1) }
        }

        mockMvc.get("/api/admin/reports/station-revenue") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/admin/reports/passenger-flow") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/admin/audit?limit=10") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/admin/tariffs") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/admin/refund-policies") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        mockMvc.delete("/api/announcements/$announcementId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `trip cancel rejected when issued tickets exist BR-SCH-025`() {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripId = createTrip(departureTime, adminAuth)
        waitForOutbox()
        sellTicket(tripId, adminAuth, seatNumber = 15)

        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"CANCELLED"}"""
        }.andExpect { status { isConflict() } }
    }

    private fun createTrip(departureTime: Instant, authHeader: String): String {
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
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
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun sellTicket(tripId: String, authHeader: String, seatNumber: Int): String {
        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":$seatNumber}"""
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
                  "passengerName":"Boarding Test",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(ticketResponse, "id")
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
