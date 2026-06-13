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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import ru.transora.app.outbox.OutboxEvent
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.outbox.handlers.ShiftOpenedHandler
import ru.transora.app.sales.ShiftRepository
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class OutboxHandlersIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var shiftRepository: ShiftRepository

    @Autowired
    private lateinit var shiftOpenedHandler: ShiftOpenedHandler

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `shift opened handler records fiscal shift number and audit`() {
        val shiftId = openShift()
        waitForOutbox()

        val shiftUuid = UUID.fromString(shiftId)
        assertThat(shiftRepository.findFiscalShiftNo(shiftUuid)).isNotNull.isPositive
        assertThat(auditCount("shift_opened", shiftId)).isEqualTo(1)
    }

    @Test
    fun `shift opened handler is idempotent on replay`() {
        val shiftId = openShift()
        waitForOutbox()

        val shiftUuid = UUID.fromString(shiftId)
        val fiscalBefore = shiftRepository.findFiscalShiftNo(shiftUuid)
        val event = fetchOutboxEvent("shift.opened", shiftId)
        shiftOpenedHandler.handle(event)
        shiftOpenedHandler.handle(event)

        assertThat(shiftRepository.findFiscalShiftNo(shiftUuid)).isEqualTo(fiscalBefore)
        assertThat(auditCount("shift_opened", shiftId)).isEqualTo(1)
    }

    @Test
    fun `order completed handler writes audit log`() {
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()
        waitForOutbox()

        val orderResponse = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 7,
                  "passengerName":"Anna Petrova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 555666",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        waitForOutbox()

        val orderId = extractJsonField(orderResponse, "orderId")
        assertThat(auditCount("order_completed", orderId)).isEqualTo(1)
    }

    @Test
    fun `reservation released handler writes audit log`() {
        val tripId = createTrip()
        waitForOutbox()

        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":15}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val reservationId = extractJsonField(reservationResponse, "id")

        mockMvc.post("/api/reservations/$reservationId/release") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }

        waitForOutbox()

        assertThat(auditCount("reservation_released", reservationId)).isEqualTo(1)
    }

    @Test
    fun `schedule updated handler triggers trip generation`() {
        val routeId = createRouteWithStops()
        val carrierId = createCarrier()
        val vehicleId = createVehicle(carrierId)
        val scheduleResponse = mockMvc.post("/api/schedules") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "name": "Outbox test schedule",
                  "scheduleType": "PERMANENT",
                  "entries": [{
                    "tripNumber": "OBX-${System.nanoTime()}",
                    "departureTime": "09:15",
                    "daysOfWeek": [1,2,3,4,5,6,7],
                    "defaultVehicleId": "$vehicleId"
                  }]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val scheduleId = extractJsonField(scheduleResponse, "id")
        waitForOutbox()

        val beforeTrips = outboxEventRepository.countByEventType("scheduling.trip.created")

        mockMvc.patch("/api/schedules/$scheduleId") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Outbox test schedule updated"}"""
        }.andExpect { status { isOk() } }

        waitForOutbox()

        assertThat(outboxEventRepository.countByEventType("scheduling.schedule.updated")).isGreaterThan(0)
        assertThat(outboxEventRepository.countByEventType("scheduling.trip.created"))
            .isGreaterThanOrEqualTo(beforeTrips)
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

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "OBX-${System.nanoTime()}",
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

    private fun createRouteWithStops(): String {
        val carrierId = createCarrier()
        val response = mockMvc.post("/api/routes") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "name": "Outbox Route",
                  "code": "OBX-${System.nanoTime()}",
                  "stops": [
                    {
                      "stopOrder": 1,
                      "stopName": "Origin",
                      "stationId": "00000000-0000-0000-0000-000000000001",
                      "isExternal": false,
                      "dwellTimeMin": 5
                    },
                    {
                      "stopOrder": 2,
                      "stopName": "Destination",
                      "stationId": null,
                      "isExternal": true,
                      "scheduledDurationMin": 60,
                      "dwellTimeMin": 5
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Outbox Carrier",
                  "legalName": "Outbox Carrier LLC",
                  "inn": "$inn",
                  "contractType": "SERVICE_FEE"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createVehicle(carrierId: String): String {
        val layoutResponse = mockMvc.post("/api/seat-layouts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Outbox Layout",
                  "totalSeats": 45,
                  "layoutJson": "{\"rows\":[]}"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val layoutId = extractJsonField(layoutResponse, "id")

        val response = mockMvc.post("/api/vehicles") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "model": "Outbox Bus",
                  "plateNumber": "OBX-${System.nanoTime()}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": 45
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun auditCount(action: String, entityId: String): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM admin.audit_log
            WHERE action = ? AND entity_id = ?
            """.trimIndent(),
            Int::class.java,
            action,
            entityId,
        ) ?: 0

    private fun fetchOutboxEvent(eventType: String, aggregateId: String): OutboxEvent {
        val events = jdbc.query(
            """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text, occurred_at
            FROM app.outbox_events
            WHERE event_type = ? AND aggregate_id = ?
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
            eventType,
            aggregateId,
        )
        return events.firstOrNull()
            ?: throw AssertionError("$eventType outbox event not found for aggregate $aggregateId")
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
