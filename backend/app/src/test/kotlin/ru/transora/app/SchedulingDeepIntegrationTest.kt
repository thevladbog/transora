package ru.transora.app

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class SchedulingDeepIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var adminAuth: String
    private lateinit var dispatcherAuth: String

    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
    }

    @Test
    fun `from-route creates trip with stops`() {
        val routeId = createRoute()
        val vehicleId = createVehicle(createCarrier(), 40)
        val tripDate = LocalDate.now().plusDays(3)

        val tripId = extractJsonField(
            mockMvc.post("/api/trips/from-route") {
                header(HttpHeaders.AUTHORIZATION, adminAuth)
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "routeId": "$routeId",
                      "tripDate": "$tripDate",
                      "tripNumber": "201",
                      "departureTime": "08:30",
                      "vehicleId": "$vehicleId",
                      "openSales": true
                    }
                """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.status") { value("OPEN") }
                jsonPath("$.routeId") { value(routeId) }
            }.andReturn().response.contentAsString,
            "id",
        )

        mockMvc.get("/api/trips/$tripId/stops") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].stopOrder") { value(1) }
            jsonPath("$[1].stopOrder") { value(2) }
        }
    }

    @Test
    fun `OPEN without vehicle rejected BR-SCH-023`() {
        val routeId = createRoute()

        mockMvc.patch("/api/trips/${createPlannedTrip(routeId)}") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN"}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `stop depart and arrive updates trip status`() {
        val routeId = createRoute()
        val vehicleId = createVehicle(createCarrier(), 30)
        val tripId = createPlannedTrip(routeId, vehicleId)

        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN"}"""
        }.andExpect { status { isOk() } }
        waitForOutbox()

        val stopsJson = mockMvc.get("/api/trips/$tripId/stops") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val firstStopId = extractJsonArrayField(stopsJson, 0, "id")
        val lastStopId = extractJsonArrayField(stopsJson, 1, "id")

        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.trip.status") { value("DEPARTED") }
        }

        mockMvc.post("/api/trips/$tripId/stops/$lastStopId/arrive") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }

        mockMvc.get("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.trip.status") { value("ARRIVED") }
        }
    }

    @Test
    fun `vehicle swap before departure resizes inventory`() {
        val routeId = createRoute()
        val carrierId = createCarrier()
        val vehicle40 = createVehicle(carrierId, 40, "PLATE-40-${System.nanoTime()}")
        val vehicle50 = createVehicle(carrierId, 50, "PLATE-50-${System.nanoTime()}")
        val tripId = createPlannedTrip(routeId, vehicle40)

        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN","vehicleId":"$vehicle40"}"""
        }.andExpect { status { isOk() } }
        waitForOutbox()

        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"vehicleId":"$vehicle50"}"""
        }.andExpect { status { isOk() } }
        waitForOutbox()

        mockMvc.get("/api/trips/$tripId/seats") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(50) }
        }
    }

    @Test
    fun `EXCEPTION schedule supersedes PERMANENT trip on same date`() {
        val routeId = createRoute()
        val vehicleId = createVehicle(createCarrier(), 35)
        val tripDate = LocalDate.now().plusDays(5)

        createSchedule(routeId, "PERMANENT", null, null, vehicleId, "301", listOf(1, 2, 3, 4, 5, 6, 7))
        mockMvc.post("/api/schedules/generate?fromDate=$tripDate&horizonDays=1") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.createdCount") { value(1) }
        }

        createSchedule(routeId, "EXCEPTION", tripDate, tripDate, vehicleId, "301", listOf(tripDate.dayOfWeek.value))
        mockMvc.post("/api/schedules/generate?fromDate=$tripDate&horizonDays=1") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.createdCount") { value(1) }
            jsonPath("$.cancelledCount") { value(1) }
        }
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Test Carrier",
                  "legalName": "Test Carrier LLC",
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
                  "name": "Deep Test Route",
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

    private fun createVehicle(carrierId: String, seats: Int, plate: String? = null): String {
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
                  "plateNumber": "${plate ?: "TST-${System.nanoTime()}"}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": $seats
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createPlannedTrip(routeId: String, vehicleId: String? = null): String {
        val tripDate = LocalDate.now().plusDays(3)
        val vehicleLine = if (vehicleId != null) {
            ""","vehicleId": "$vehicleId""""
        } else {
            ""
        }
        val response = mockMvc.post("/api/trips/from-route") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "tripNumber": "${System.nanoTime()}",
                  "departureTime": "08:30"$vehicleLine,
                  "openSales": false
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createSchedule(
        routeId: String,
        type: String,
        validFrom: LocalDate?,
        validTo: LocalDate?,
        vehicleId: String,
        tripNumber: String,
        daysOfWeek: List<Int>,
    ) {
        val datesPart = if (validFrom != null && validTo != null) {
            ""","validFrom":"$validFrom","validTo":"$validTo""""
        } else {
            ""
        }
        mockMvc.post("/api/schedules") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "name": "$type schedule $tripNumber",
                  "scheduleType": "$type"
                  $datesPart,
                  "entries": [
                    {
                      "tripNumber": "$tripNumber",
                      "departureTime": "07:00",
                      "daysOfWeek": ${daysOfWeek.joinToString(prefix = "[", postfix = "]")},
                      "defaultVehicleId": "$vehicleId"
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }

    private fun extractJsonArrayField(json: String, index: Int, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.findAll(json).elementAt(index).groupValues[1]
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
