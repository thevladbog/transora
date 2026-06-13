package ru.transora.app

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class DispatcherToolingIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var adminAuth: String
    private lateinit var dispatcherAuth: String
    private lateinit var cashierAuth: String

    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
        cashierAuth = TestAuth.bearer(TestAuth.loginAsCashier(mockMvc))
    }

    @Test
    fun `release seat block allows reservation again`() {
        val tripId = createFlatTrip()
        waitForOutbox()

        val blockJson = mockMvc.post("/api/seat-blocks") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":20,"reason":"Maintenance"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val blockId = extractJsonField(blockJson, "id")

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":20}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/seat-blocks/$blockId/release") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.releasedAt") { exists() }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":20}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `only block creator or admin can release seat block`() {
        val tripId = createFlatTrip()
        waitForOutbox()

        val blockJson = mockMvc.post("/api/seat-blocks") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":21,"reason":"VIP hold"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val blockId = extractJsonField(blockJson, "id")

        val otherDispatcherAuth = createDispatcherUser("${System.nanoTime()}")
        mockMvc.post("/api/seat-blocks/$blockId/release") {
            header(HttpHeaders.AUTHORIZATION, otherDispatcherAuth)
        }.andExpect { status { isForbidden() } }

        mockMvc.post("/api/seat-blocks/$blockId/release") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `pause and resume sales restriction toggles quota enforcement`() {
        val tripId = createFlatTrip()
        waitForOutbox()

        val restrictionJson = mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","allowedSeats":[1,2,3,4,5]}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val restrictionId = extractJsonField(restrictionJson, "id")

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":6}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/sales-restrictions/$restrictionId/pause") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PAUSED") }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":6}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/sales-restrictions/$restrictionId/resume") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":7}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `schedule entry restriction applies to generated trips`() {
        val routeId = createRoute()
        val vehicleId = createVehicle(createCarrier(), 45)
        val tripDate = LocalDate.now().plusDays(4)
        val tripNumber = "DT${System.nanoTime()}"

        val scheduleJson = mockMvc.post("/api/schedules") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "name": "Dispatcher quota schedule",
                  "scheduleType": "PERMANENT",
                  "entries": [
                    {
                      "tripNumber": "$tripNumber",
                      "departureTime": "09:00",
                      "daysOfWeek": [${tripDate.dayOfWeek.value}],
                      "defaultVehicleId": "$vehicleId"
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val scheduleEntryId = extractJsonArrayField(scheduleJson, "entries", "id")

        mockMvc.post("/api/schedules/generate?fromDate=$tripDate&horizonDays=1") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        waitForOutbox()

        val tripId = findGeneratedTripId(scheduleEntryId, tripDate)

        openGeneratedTrip(tripId)

        mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scheduleEntryId":"$scheduleEntryId",
                  "allowedSeats":[40,41,42,43,44,45]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.scope") { value("SCHEDULE_ENTRY") }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":5,"fromStopOrder":1,"toStopOrder":2}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":40,"fromStopOrder":1,"toStopOrder":2}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `trip-specific restriction overrides schedule entry rule`() {
        val routeId = createRoute()
        val vehicleId = createVehicle(createCarrier(), 45)
        val tripDate = LocalDate.now().plusDays(5)
        val tripNumber = "DT2${System.nanoTime()}"

        val scheduleJson = mockMvc.post("/api/schedules") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "name": "Override schedule",
                  "scheduleType": "PERMANENT",
                  "entries": [
                    {
                      "tripNumber": "$tripNumber",
                      "departureTime": "10:00",
                      "daysOfWeek": [${tripDate.dayOfWeek.value}],
                      "defaultVehicleId": "$vehicleId"
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val scheduleEntryId = extractJsonArrayField(scheduleJson, "entries", "id")

        mockMvc.post("/api/schedules/generate?fromDate=$tripDate&horizonDays=1") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        waitForOutbox()

        val tripId = findGeneratedTripId(scheduleEntryId, tripDate)

        openGeneratedTrip(tripId)

        mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "scheduleEntryId":"$scheduleEntryId",
                  "allowedSeats":[30,31,32,33,34,35]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","allowedSeats":[1,2,3,4,5]}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":30,"fromStopOrder":1,"toStopOrder":2}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":3,"fromStopOrder":1,"toStopOrder":2}"""
        }.andExpect { status { isOk() } }
    }

    private fun openGeneratedTrip(tripId: String) {
        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN"}"""
        }.andExpect { status { isOk() } }
    }

    private fun createFlatTrip(): String {
        val departureTime = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "DT-${System.nanoTime()}",
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

    private fun createDispatcherUser(loginSuffix: String): String {
        val login = "disp${loginSuffix.filter { it.isLetterOrDigit() }.takeLast(12)}"
        mockMvc.post("/api/admin/users") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "login": "$login",
                  "password": "$login",
                  "fullName": "Extra Dispatcher",
                  "assignments": [{
                    "stationId": "$t1StationId",
                    "roleCode": "DISPATCHER"
                  }]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
        return TestAuth.bearer(TestAuth.login(mockMvc, login, login))
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Dispatcher Test Carrier",
                  "legalName": "Dispatcher Test Carrier LLC",
                  "inn": "$inn",
                  "contractType": "SERVICE_FEE"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createRoute(): String {
        val carrierId = createCarrier()
        val response = mockMvc.post("/api/routes") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "name": "Dispatcher Route",
                  "code": "DTR-${System.nanoTime()}",
                  "stops": [
                    {
                      "stopOrder": 1,
                      "stopName": "Origin",
                      "stationId": "$t1StationId",
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
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createVehicle(carrierId: String, seats: Int): String {
        val layoutResponse = mockMvc.post("/api/seat-layouts") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Layout-$seats",
                  "totalSeats": $seats,
                  "layoutJson": "{\"rows\":[]}"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val layoutId = extractJsonField(layoutResponse, "id")

        val response = mockMvc.post("/api/vehicles") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "model": "Test Bus",
                  "plateNumber": "DT${System.nanoTime()}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": $seats
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun findGeneratedTripId(scheduleEntryId: String, tripDate: LocalDate): String =
        jdbc.queryForObject(
            """
            SELECT id
            FROM scheduling.trips
            WHERE schedule_entry_id = ? AND trip_date = ?
            """.trimIndent(),
            UUID::class.java,
            UUID.fromString(scheduleEntryId),
            java.sql.Date.valueOf(tripDate),
        )?.toString() ?: throw AssertionError("Generated trip not found for entry $scheduleEntryId on $tripDate")

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }

    private fun extractJsonArrayField(json: String, arrayField: String, field: String): String {
        val arrayPattern = """"$arrayField"\s*:\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val arrayContent = arrayPattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Array $arrayField not found in $json")
        return extractJsonField(arrayContent, field)
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
