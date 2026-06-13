package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import ru.transora.app.notifications.DisplayBoardRecord
import ru.transora.app.notifications.DisplayBoardRepository
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardsIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var displayBoardRepository: DisplayBoardRepository

    @LocalServerPort
    private var port: Int = 0

    private lateinit var adminAuth: String
    private lateinit var dispatcherAuth: String

    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
    }

    @Test
    fun `intermediate station arrivals shows in-transit trip with stop estimated arrival`() {
        val t2Code = "T2-${System.nanoTime()}"
        val t3Code = "T3-${System.nanoTime()}"
        val t2Id = createStation(t2Code, "Hub Station")
        val t3Id = createStation(t3Code, "Final Station")
        val routeId = createThreeStopRoute(t2Id, t3Id, t2Code, t3Code)
        val vehicleId = createVehicle(createCarrier(), 30)
        val tripId = createRouteTrip(routeId, vehicleId, tripNumber = "501")

        openTrip(tripId, vehicleId)
        waitForOutbox()

        mockMvc.get("/api/board/arrivals?stationCode=$t2Code&windowAfterMin=10080") {
        }.andExpect {
            status { isOk() }
            jsonPath("$.arrivals.length()") { value(0) }
        }

        val firstStopId = firstStopId(tripId)
        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }
        waitForOutbox()

        mockMvc.get("/api/board/arrivals?stationCode=$t2Code&windowAfterMin=10080") {
        }.andExpect {
            status { isOk() }
            jsonPath("$.arrivals.length()") { value(1) }
            jsonPath("$.arrivals[0].tripId") { value(tripId) }
            jsonPath("$.arrivals[0].displayStatus") { value("ARRIVING") }
            jsonPath("$.arrivals[0].direction") { value("TRANSIT") }
        }

        mockMvc.get("/api/board/arrivals?stationCode=$t3Code&windowAfterMin=10080") {
        }.andExpect {
            status { isOk() }
            jsonPath("$.arrivals.length()") { value(1) }
            jsonPath("$.arrivals[0].tripId") { value(tripId) }
        }
    }

    @Test
    fun `first stop depart removes trip from origin departures board`() {
        val routeId = createTwoStopRoute()
        val vehicleId = createVehicle(createCarrier(), 25)
        val tripId = createRouteTrip(routeId, vehicleId)

        openTrip(tripId, vehicleId)
        waitForOutbox()

        assertTripOnDeparturesBoard(tripId, present = true)

        val firstStopId = firstStopId(tripId)
        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }
        waitForOutbox()

        assertTripOnDeparturesBoard(tripId, present = false)
    }

    @Test
    fun `trip delay updates board row and refreshes via outbox`() {
        val routeId = createTwoStopRoute()
        val vehicleId = createVehicle(createCarrier(), 25)
        val tripId = createRouteTrip(routeId, vehicleId)

        openTrip(tripId, vehicleId)
        waitForOutbox()

        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"delayMinutes":20,"delayReason":"Traffic"}"""
        }.andExpect { status { isOk() } }
        waitForOutbox()

        val boardBody = mockMvc.get("/api/board/departures?stationCode=T1&windowAfterMin=10080") {
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        assertThat(boardBody).contains("\"tripId\":\"$tripId\"")
        assertThat(boardBody).contains("\"delayMinutes\":20")
        assertThat(boardBody).contains("\"displayStatus\":\"DELAYED\"")
    }

    @Test
    fun `legacy flat trip appears on departures board`() {
        val departureTime = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val tripResponse = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "777",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "1",
                  "seatCount": 40
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val tripId = extractJsonField(tripResponse, "id")
        waitForOutbox()

        val boardBody = mockMvc.get("/api/board/departures?stationCode=T1&windowAfterMin=10080") {
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        assertThat(boardBody).contains("\"tripId\":\"$tripId\"")
        assertThat(boardBody).contains("\"route\":\"777\"")
        assertThat(boardBody).contains("\"direction\":\"DEPARTURE\"")
    }

    @Test
    fun `websocket receives updated board state after stop depart`() {
        val routeId = createTwoStopRoute()
        val vehicleId = createVehicle(createCarrier(), 25)
        val tripId = createRouteTrip(routeId, vehicleId)

        openTrip(tripId, vehicleId)
        waitForOutbox()

        val boardId = UUID.randomUUID()
        displayBoardRepository.insert(
            DisplayBoardRecord(
                id = boardId,
                stationCode = "T1",
                boardType = "DEPARTURES",
                platformNumber = null,
                name = "Test Departures Board",
                isActive = true,
            ),
        )

        val messages = ConcurrentLinkedQueue<String>()
        val connected = CountDownLatch(1)
        val handler = object : TextWebSocketHandler() {
            override fun afterConnectionEstablished(session: WebSocketSession) {
                connected.countDown()
            }

            override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
                messages.add(message.payload)
            }
        }

        val client = StandardWebSocketClient()
        val session = client.execute(
            handler,
            "ws://localhost:$port/ws/board/$boardId",
        ).get(10, TimeUnit.SECONDS)

        assertThat(connected.await(10, TimeUnit.SECONDS)).isTrue()
        pollMessage(messages, 10, TimeUnit.SECONDS)

        val firstStopId = firstStopId(tripId)
        mockMvc.post("/api/trips/$tripId/stops/$firstStopId/depart") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
        }.andExpect { status { isOk() } }
        waitForOutbox()

        val updated = pollMessage(messages, 15, TimeUnit.SECONDS)
        assertThat(updated).isNotNull()
        assertThat(updated).contains("BOARD_STATE_FULL")
        assertThat(updated).doesNotContain(tripId)

        session.close()
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

    private fun createTwoStopRoute(): String {
        val carrierId = createCarrier()
        val response = mockMvc.post("/api/routes") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "name": "Board Two Stop",
                  "code": "BTS-${System.nanoTime()}",
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
                  "name": "Board Three Stop",
                  "code": "B3S-${System.nanoTime()}",
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

    private fun createRouteTrip(routeId: String, vehicleId: String, tripNumber: String? = null): String {
        val tripDate = LocalDate.now(ZoneOffset.UTC).plusDays(1)
        val number = tripNumber ?: "${System.nanoTime()}"
        val response = mockMvc.post("/api/trips/from-route") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "tripNumber": "$number",
                  "departureTime": "10:30",
                  "vehicleId": "$vehicleId",
                  "openSales": false
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openTrip(tripId: String, vehicleId: String) {
        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN","vehicleId":"$vehicleId"}"""
        }.andExpect { status { isOk() } }
    }

    private fun firstStopId(tripId: String): String {
        val stopsJson = mockMvc.get("/api/trips/$tripId/stops") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonArrayField(stopsJson, 0, "id")
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Board Carrier",
                  "legalName": "Board Carrier LLC",
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
                  "model": "Board Bus",
                  "plateNumber": "BRD-${System.nanoTime()}",
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

    private fun assertTripOnDeparturesBoard(tripId: String, present: Boolean) {
        val body = mockMvc.get("/api/board/departures?stationCode=T1&windowAfterMin=10080") {
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val hasTrip = body.contains("\"tripId\":\"$tripId\"")
        assertThat(hasTrip).isEqualTo(present)
    }

    private fun pollMessage(
        messages: ConcurrentLinkedQueue<String>,
        timeout: Long,
        unit: TimeUnit,
    ): String? {
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (System.currentTimeMillis() < deadline) {
            messages.poll()?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}
