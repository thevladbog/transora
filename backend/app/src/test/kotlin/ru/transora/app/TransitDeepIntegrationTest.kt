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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class TransitDeepIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var adminAuth: String
    private lateinit var cashierAuth: String

    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        cashierAuth = TestAuth.bearer(TestAuth.loginAsCashier(mockMvc))
    }

    @Test
    fun `transit gate lifecycle enforces BR-INV-040 through BR-INV-045`() {
        val t2Code = "TG2-${System.nanoTime()}"
        val t3Code = "TG3-${System.nanoTime()}"
        val t2Id = createStation(t2Code, "Transit Hub")
        val t3Id = createStation(t3Code, "Transit Final")
        val routeId = createThreeStopRoute(t2Id, t3Id, t2Code, t3Code)
        val vehicleId = createVehicle(createCarrier(), 45)
        val tripId = createRouteTrip(routeId, vehicleId, tripNumber = "701")
        openTrip(tripId, vehicleId)
        waitForOutbox()

        mockMvc.get("/api/transit-gates?tripId=$tripId") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].stopOrder") { value(2) }
            jsonPath("$[0].stationId") { value(t2Id) }
            jsonPath("$[0].status") { value("AWAITING_ARRIVAL") }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":40,"fromStopOrder":2,"toStopOrder":3}"""
        }.andExpect {
            status { isConflict() }
        }

        val stopsJson = mockMvc.get("/api/trips/$tripId/stops") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val firstStopId = extractJsonArrayField(stopsJson, 0, "id")
        val hubStopId = extractJsonArrayField(stopsJson, 1, "id")

        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher")))
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/trips/$tripId/stops/$hubStopId/arrive") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher")))
        }.andExpect { status { isOk() } }
        waitForOutbox()

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":40,"fromStopOrder":2,"toStopOrder":3}"""
        }.andExpect { status { isConflict() } }

        val gateId = extractJsonField(
            mockMvc.get("/api/transit-gates?tripId=$tripId") {
                header(HttpHeaders.AUTHORIZATION, adminAuth)
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
            "id",
        )

        val t2DispatcherAuth = TestAuth.bearer(
            TestAuth.loginWithStation(mockMvc, "dispatcher", "dispatcher", t2Id),
        )
        val t2CashierAuth = TestAuth.bearer(
            TestAuth.loginWithStation(mockMvc, "cashier", "cashier", t2Id),
        )

        mockMvc.post("/api/transit-gates/$gateId/open") {
            header(HttpHeaders.AUTHORIZATION, t2DispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"availableSeats":[40,41]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.availableSeats[0]") { value(40) }
            jsonPath("$.availableSeats[1]") { value(41) }
        }
        waitForOutbox()

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, t2CashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":40,"fromStopOrder":2,"toStopOrder":3}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, t2CashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":39,"fromStopOrder":2,"toStopOrder":3}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/transit-gates/$gateId/close") {
            header(HttpHeaders.AUTHORIZATION, t2DispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CLOSED") }
        }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, t2CashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":41,"fromStopOrder":2,"toStopOrder":3}"""
        }.andExpect { status { isConflict() } }

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":5,"fromStopOrder":1,"toStopOrder":2}"""
        }.andExpect { status { isOk() } }

        val queueBody = mockMvc.get("/api/announcements/queue") {
            header(HttpHeaders.AUTHORIZATION, t2DispatcherAuth)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        assertThat(queueBody).contains("Открыты транзитные продажи")
        assertThat(queueBody).contains("701")
        assertThat(queueBody).contains("40")
    }

    private fun createStation(code: String, name: String): String {
        val response = mockMvc.post("/api/stations") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "$code",
                  "name": "$name",
                  "city": "Transora",
                  "timezone": "Europe/Moscow"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createThreeStopRoute(
        t2Id: String,
        t3Id: String,
        t2Code: String,
        t3Code: String,
    ): String {
        val carrierId = createCarrier()
        val response = mockMvc.post("/api/routes") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "name": "Transit Three Stop",
                  "code": "T3S-${System.nanoTime()}",
                  "stops": [
                    {
                      "stopOrder": 1,
                      "stopName": "Origin T1",
                      "stationId": "$t1StationId",
                      "isExternal": false,
                      "dwellTimeMin": 5
                    },
                    {
                      "stopOrder": 2,
                      "stopName": "Hub $t2Code",
                      "stationId": "$t2Id",
                      "isExternal": false,
                      "scheduledDurationMin": 45,
                      "dwellTimeMin": 5
                    },
                    {
                      "stopOrder": 3,
                      "stopName": "Final $t3Code",
                      "stationId": "$t3Id",
                      "isExternal": false,
                      "scheduledDurationMin": 30,
                      "dwellTimeMin": 5
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createRouteTrip(routeId: String, vehicleId: String, tripNumber: String): String {
        val tripDate = LocalDate.now(ZoneOffset.UTC).plusDays(1)
        val response = mockMvc.post("/api/trips/from-route") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "tripNumber": "$tripNumber",
                  "departureTime": "11:00",
                  "vehicleId": "$vehicleId",
                  "openSales": false
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openTrip(tripId: String, vehicleId: String) {
        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN","vehicleId":"$vehicleId"}"""
        }.andExpect { status { isOk() } }
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Transit Carrier",
                  "legalName": "Transit Carrier LLC",
                  "inn": "$inn",
                  "contractType": "SERVICE_FEE"
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
                  "model": "Transit Bus",
                  "plateNumber": "TRN-${System.nanoTime()}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": $seats
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
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
