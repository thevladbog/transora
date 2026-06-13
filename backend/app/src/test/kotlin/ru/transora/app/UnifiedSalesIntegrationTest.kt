package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.hardware.FiscalReceiptRequest
import ru.transora.app.hardware.FiscalReceiptResult
import ru.transora.app.hardware.FiscalShiftOpenRequest
import ru.transora.app.hardware.FiscalShiftOpenResult
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.hardware.PrintTicketRequest
import ru.transora.app.hardware.PrintTicketResult
import ru.transora.app.inventory.ReservationRepository
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import ru.transora.inventory.domain.ReservationStatus
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class UnifiedSalesIntegrationTest : IntegrationTestBase() {
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
    fun `issue ticket without passenger docs returns bad request`() {
        val tripId = createTrip()
        waitForOutbox()
        val reservationId = createReservation(tripId, 7)
        val shiftId = openShift()

        mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$shiftId",
                  "passengerName":"Ivan Petrov"
                }
            """.trimIndent()
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `reserve then issue uses unified saga with order and fiscal`() {
        val tripId = createTrip()
        waitForOutbox()
        val reservationId = createReservation(tripId, 9)
        val shiftId = openShift()

        val beforeOrders = outboxEventRepository.countByEventType("sales.order.completed")

        val ticketJson = mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = ticketIssueBody(reservationId, shiftId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ISSUED") }
            jsonPath("$.orderId") { exists() }
            jsonPath("$.ticketNumber") { exists() }
        }.andReturn().response.contentAsString

        waitForOutbox()
        assertThat(ticketJson).contains("\"orderId\"")
        assertThat(outboxEventRepository.countByEventType("sales.order.completed"))
            .isEqualTo(beforeOrders + 1)
    }

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "501",
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

    private fun ticketIssueBody(reservationId: String, shiftId: String): String =
        """
            {
              "reservationId":"$reservationId",
              "shiftId":"$shiftId",
              "passengerName":"Anna Smirnova",
              "docType":"PASSPORT_RF",
              "docNumber":"4510 123456",
              "paymentType":"CASH"
            }
        """.trimIndent()

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

@AutoConfigureMockMvc
@Import(UnifiedSalesFiscalFailureIntegrationTest.FailingFiscalConfiguration::class)
class UnifiedSalesFiscalFailureIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `fiscal failure keeps reservation active BR-SAL-008`() {
        val departureTime = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripResponse = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "502",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "3",
                  "seatCount": 45
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val tripId = extractJsonField(tripResponse, "id")
        waitForOutbox()

        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":11}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val reservationId = extractJsonField(reservationResponse, "id")

        val shiftResponse = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val shiftId = extractJsonField(shiftResponse, "id")

        mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$shiftId",
                  "passengerName":"Anna Smirnova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isConflict() } }

        val reservation = reservationRepository.findByIdForUpdate(UUID.fromString(reservationId))
        assertThat(reservation).isNotNull
        assertThat(reservation!!.status).isEqualTo(ReservationStatus.ACTIVE)
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

    @TestConfiguration
    class FailingFiscalConfiguration {
        @Bean
        @Primary
        fun hardwareAgentClient(): HardwareAgentClient = object : HardwareAgentClient {
            override fun openFiscalShift(request: FiscalShiftOpenRequest): FiscalShiftOpenResult =
                FiscalShiftOpenResult(fiscalShiftNo = 1)

            override fun printFiscalReceipt(request: FiscalReceiptRequest): FiscalReceiptResult =
                throw RuntimeException("fiscal device offline")

            override fun printFiscalRefund(request: FiscalReceiptRequest): FiscalReceiptResult =
                throw RuntimeException("fiscal device offline")

            override fun printZReport(request: ru.transora.app.hardware.ShiftZReportRequest): FiscalReceiptResult =
                throw RuntimeException("fiscal device offline")

            override fun printTicket(request: PrintTicketRequest): PrintTicketResult =
                PrintTicketResult(ticketId = request.ticketId, printed = true)
        }
    }
}
