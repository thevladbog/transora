package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.transora.app.outbox.OutboxEvent
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.outbox.handlers.ShiftClosedHandler
import ru.transora.app.sales.FiscalReceiptRepository
import ru.transora.app.sales.ShiftRepository
import ru.transora.app.test.TestAuth
import ru.transora.sales.domain.FiscalReceiptType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class ShiftCloseZReportIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var fiscalReceiptRepository: FiscalReceiptRepository

    @Autowired
    private lateinit var shiftRepository: ShiftRepository

    @Autowired
    private lateinit var shiftClosedHandler: ShiftClosedHandler

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `closing shift records Z-report fiscal receipt`() {
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 8,
                  "passengerName":"Maria Volkova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 111222",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }

        waitForOutbox()

        mockMvc.post("/api/shifts/$shiftId/close") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }

        waitForOutbox()

        val shiftUuid = UUID.fromString(shiftId)
        val zReport = fiscalReceiptRepository.findByShiftAndType(shiftUuid, FiscalReceiptType.Z_REPORT)
        assertThat(zReport).isNotNull
        assertThat(zReport!!.fiscalSign).isNotBlank
        assertThat(zReport.fiscalDocNo).isPositive

        val linkedReceiptId = shiftRepository.findZReportReceiptId(shiftUuid)
        assertThat(linkedReceiptId).isEqualTo(zReport.id)
    }

    @Test
    fun `shift closed handler is idempotent on replay`() {
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 10,
                  "passengerName":"Pavel Sidorov",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 333444",
                  "paymentType":"CARD"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }

        waitForOutbox()

        mockMvc.post("/api/shifts/$shiftId/close") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }

        waitForOutbox()

        val shiftUuid = UUID.fromString(shiftId)
        val event = fetchShiftClosedEvent(shiftUuid)
        shiftClosedHandler.handle(event)
        shiftClosedHandler.handle(event)

        assertThat(
            fiscalReceiptRepository.countByShiftAndType(shiftUuid, FiscalReceiptType.Z_REPORT),
        ).isEqualTo(1)
    }

    private fun fetchShiftClosedEvent(shiftId: UUID): OutboxEvent {
        val events = jdbc.query(
            """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text, occurred_at
            FROM app.outbox_events
            WHERE event_type = 'shift.closed' AND aggregate_id = ?
            ORDER BY occurred_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                OutboxEvent(
                    id = rs.getObject("id", UUID::class.java),
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getString("aggregate_id"),
                    eventType = rs.getString("event_type"),
                    payload = rs.getString("payload"),
                    occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                )
            },
            shiftId.toString(),
        )
        return events.firstOrNull()
            ?: throw AssertionError("shift.closed outbox event not found for shift $shiftId")
    }

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
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
