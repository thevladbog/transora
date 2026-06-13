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
class SegmentDeepIntegrationTest : IntegrationTestBase() {
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
    fun `non-overlapping segment sales on same seat and overlap rejection`() {
        val t2Code = "SG2-${System.nanoTime()}"
        val t3Code = "SG3-${System.nanoTime()}"
        val t2Id = createStation(t2Code, "Segment Hub")
        val t3Id = createStation(t3Code, "Segment Final")
        val routeCode = "S3S-${System.nanoTime()}"
        val routeId = createThreeStopRoute(t2Id, t3Id, t2Code, t3Code, routeCode)
        val vehicleId = createVehicle(createCarrier(), 45)
        val tripId = createRouteTrip(routeId, vehicleId, tripNumber = "801")
        createTariffs("801")
        openTrip(tripId, vehicleId)
        waitForOutbox()

        val shiftId = openShift(adminAuth)
        val ticket1 = sellSegment(tripId, adminAuth, shiftId, seatNumber = 10, fromStop = 1, toStop = 2)

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":10,"fromStopOrder":1,"toStopOrder":3}"""
        }.andExpect { status { isConflict() } }

        openTransitGateAtT2(tripId, t2Id, seats = listOf(10, 11, 12))

        val ticket2 = sellSegment(tripId, adminAuth, shiftId, seatNumber = 10, fromStop = 2, toStop = 3)
        assertThat(ticket2).isNotBlank()

        mockMvc.post("/api/tickets/$ticket1/refund") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }

        sellSegment(tripId, adminAuth, shiftId, seatNumber = 10, fromStop = 1, toStop = 2)

        mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":10,"fromStopOrder":1,"toStopOrder":3}"""
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `station seat map reflects transit gate and sales restriction`() {
        val t2Code = "SM2-${System.nanoTime()}"
        val t3Code = "SM3-${System.nanoTime()}"
        val t2Id = createStation(t2Code, "Map Hub")
        val t3Id = createStation(t3Code, "Map Final")
        val routeCode = "SM3S-${System.nanoTime()}"
        val routeId = createThreeStopRoute(t2Id, t3Id, t2Code, t3Code, routeCode)
        val vehicleId = createVehicle(createCarrier(), 45)
        val tripId = createRouteTrip(routeId, vehicleId, tripNumber = "802")
        createTariffs("802")
        openTrip(tripId, vehicleId)
        waitForOutbox()

        val t2DispatcherAuth = TestAuth.bearer(TestAuth.loginWithStation(mockMvc, "dispatcher", "dispatcher", t2Id))
        mockMvc.post("/api/sales-restrictions") {
            header(HttpHeaders.AUTHORIZATION, t2DispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","allowedSeats":[40,41,42,43,44,45]}"""
        }.andExpect { status { isOk() } }

        val t2CashierAuth = TestAuth.bearer(TestAuth.loginWithStation(mockMvc, "cashier", "cashier", t2Id))

        mockMvc.get("/api/trips/$tripId/seats?toStopOrder=3") {
            header(HttpHeaders.AUTHORIZATION, t2CashierAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.fromStopOrder") { value(2) }
            jsonPath("$.toStopOrder") { value(3) }
            jsonPath("$.transitGate.status") { value("AWAITING_ARRIVAL") }
            jsonPath("$.seats[?(@.seatNumber == 5)].availableForStation") { value(false) }
            jsonPath("$.seats[?(@.seatNumber == 5)].restrictionReason") { value("NOT_IN_QUOTA") }
            jsonPath("$.seats[?(@.seatNumber == 40)].restrictionReason") { value("TRANSIT_CLOSED") }
        }

        openTransitGateAtT2(tripId, t2Id, seats = listOf(40, 41))

        mockMvc.get("/api/trips/$tripId/seats?toStopOrder=3") {
            header(HttpHeaders.AUTHORIZATION, t2CashierAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.transitGate.status") { value("OPEN") }
            jsonPath("$.seats[?(@.seatNumber == 40)].availableForStation") { value(true) }
            jsonPath("$.seats[?(@.seatNumber == 39)].restrictionReason") { value("NOT_IN_QUOTA") }
        }
    }

    private fun openTransitGateAtT2(tripId: String, t2Id: String, seats: List<Int>) {
        val gateId = extractJsonField(
            mockMvc.get("/api/transit-gates?tripId=$tripId") {
                header(HttpHeaders.AUTHORIZATION, adminAuth)
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
            "id",
        )
        val stopsJson = mockMvc.get("/api/trips/$tripId/stops") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val firstStopId = extractJsonArrayField(stopsJson, 0, "id")
        val hubStopId = extractJsonArrayField(stopsJson, 1, "id")
        val dispatcherT1 = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, dispatcherT1)
        }.andExpect { status { isOk() } }
        mockMvc.post("/api/trips/$tripId/stops/$hubStopId/arrive") {
            header(HttpHeaders.AUTHORIZATION, dispatcherT1)
        }.andExpect { status { isOk() } }
        val t2DispatcherAuth = TestAuth.bearer(TestAuth.loginWithStation(mockMvc, "dispatcher", "dispatcher", t2Id))
        mockMvc.post("/api/transit-gates/$gateId/open") {
            header(HttpHeaders.AUTHORIZATION, t2DispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"availableSeats":${seats}}"""
        }.andExpect { status { isOk() } }
    }

    private fun sellSegment(
        tripId: String,
        auth: String,
        shiftId: String,
        seatNumber: Int,
        fromStop: Int,
        toStop: Int,
    ): String {
        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, auth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"tripId":"$tripId","seatNumber":$seatNumber,"fromStopOrder":$fromStop,"toStopOrder":$toStop}
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val reservationId = extractJsonField(reservationResponse, "id")
        val ticketResponse = mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, auth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"reservationId":"$reservationId","shiftId":"$shiftId","passengerName":"Segment Pax","docType":"PASSPORT_RF","docNumber":"4510 123456","paymentType":"CASH"}
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(ticketResponse, "id")
    }

    private fun openShift(auth: String): String {
        val response = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"seg-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createStation(code: String, name: String): String {
        val response = mockMvc.post("/api/stations") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"code":"$code","name":"$name","city":"Transora","timezone":"Europe/Moscow"}
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createTariffs(routeNumber: String) {
        listOf(1 to 2, 2 to 3, 1 to 3).forEach { (from, to) ->
            mockMvc.post("/api/admin/tariffs") {
                header(HttpHeaders.AUTHORIZATION, adminAuth)
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "routeNumber": "$routeNumber",
                      "fromStopOrder": $from,
                      "toStopOrder": $to,
                      "priceCents": ${from * 1000L + to * 100L}
                    }
                """.trimIndent()
            }.andExpect { status { isOk() } }
        }
    }

    private fun createThreeStopRoute(
        t2Id: String,
        t3Id: String,
        t2Code: String,
        t3Code: String,
        routeCode: String,
    ): String {
        val carrierId = createCarrier()
        val response = mockMvc.post("/api/routes") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "name": "Segment Three Stop",
                  "code": "$routeCode",
                  "stops": [
                    {"stopOrder":1,"stopName":"Origin T1","stationId":"$t1StationId","isExternal":false,"dwellTimeMin":5},
                    {"stopOrder":2,"stopName":"Hub $t2Code","stationId":"$t2Id","isExternal":false,"scheduledDurationMin":45,"dwellTimeMin":5},
                    {"stopOrder":3,"stopName":"Final $t3Code","stationId":"$t3Id","isExternal":false,"scheduledDurationMin":30,"dwellTimeMin":5}
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
                  "routeId":"$routeId",
                  "tripDate":"$tripDate",
                  "tripNumber":"$tripNumber",
                  "departureTime":"09:00",
                  "vehicleId":"$vehicleId",
                  "openSales":false
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
                {"name":"Segment Carrier","legalName":"Segment LLC","inn":"$inn","contractType":"SERVICE_FEE"}
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createVehicle(carrierId: String, seats: Int): String {
        val layoutResponse = mockMvc.post("/api/seat-layouts") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Layout-$seats","totalSeats":$seats,"layoutJson":"{\"rows\":[]}"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        val layoutId = extractJsonField(layoutResponse, "id")
        val response = mockMvc.post("/api/vehicles") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId":"$carrierId",
                  "model":"Segment Bus",
                  "plateNumber":"SEG-${System.nanoTime()}",
                  "seatLayoutId":"$layoutId",
                  "totalSeats":$seats
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
            if (outboxEventRepository.countUnpublished() == 0) return
            outboxPublisher.publishPendingEvents()
            Thread.sleep(200)
        }
        throw AssertionError("Outbox events were not published in time")
    }
}
