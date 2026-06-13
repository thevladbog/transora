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
import ru.transora.app.inventory.ReservationRepository
import ru.transora.app.inventory.SeatRepository
import ru.transora.app.inventory.SeatSaleRepository
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.test.TestAuth
import ru.transora.inventory.domain.ReservationStatus
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class ReaccommodationIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var reservationRepository: ReservationRepository

    @Autowired
    private lateinit var seatRepository: SeatRepository

    @Autowired
    private lateinit var seatSaleRepository: SeatSaleRepository

    private lateinit var adminAuth: String
    private lateinit var dispatcherAuth: String

    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        dispatcherAuth = TestAuth.bearer(TestAuth.login(mockMvc, "dispatcher", "dispatcher"))
    }

    @Test
    fun `upsize preserves SOLD seat status`() {
        val routeId = createRoute()
        val carrierId = createCarrier()
        val vehicle40 = createVehicle(carrierId, 40)
        val vehicle50 = createVehicle(carrierId, 50)
        val tripId = createPlannedTrip(routeId, vehicle40)

        openTrip(tripId, vehicle40)
        waitForOutbox()
        sellTicket(tripId, seatNumber = 15)

        swapVehicle(tripId, vehicle50)
        waitForOutbox()

        val tripUuid = UUID.fromString(tripId)
        val seat15 = seatRepository.listByTrip(tripUuid).single { it.seatNumber == 15 }
        assertThat(seat15.requiresReaccommodation).isFalse()
        assertThat(seatSaleRepository.countActiveByTripSeat(tripUuid, 15)).isEqualTo(1)

        mockMvc.get("/api/trips/$tripId/seats") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(50) }
        }
    }

    @Test
    fun `downsize flags overflow SOLD seat`() {
        val routeId = createRoute()
        val carrierId = createCarrier()
        val vehicle50 = createVehicle(carrierId, 50)
        val vehicle40 = createVehicle(carrierId, 40)
        val tripId = createPlannedTrip(routeId, vehicle50)

        openTrip(tripId, vehicle50)
        waitForOutbox()
        sellTicket(tripId, seatNumber = 45)

        swapVehicle(tripId, vehicle40)
        waitForOutbox()

        val tripUuid = UUID.fromString(tripId)
        val seat45 = seatRepository.listByTrip(tripUuid).single { it.seatNumber == 45 }
        assertThat(seat45.requiresReaccommodation).isTrue()
        assertThat(seatSaleRepository.countActiveByTripSeat(tripUuid, 45)).isEqualTo(1)
    }

    @Test
    fun `downsize cancels RESERVED overflow seat`() {
        val routeId = createRoute()
        val carrierId = createCarrier()
        val vehicle50 = createVehicle(carrierId, 50)
        val vehicle40 = createVehicle(carrierId, 40)
        val tripId = createPlannedTrip(routeId, vehicle50)

        openTrip(tripId, vehicle50)
        waitForOutbox()

        val reservationId = reserveSeat(tripId, seatNumber = 48)
        swapVehicle(tripId, vehicle40)
        waitForOutbox()

        val reservation = reservationRepository.findByIdForUpdate(UUID.fromString(reservationId))
        assertThat(reservation).isNotNull
        assertThat(reservation!!.status).isEqualTo(ReservationStatus.CANCELLED)

        val tripUuid = UUID.fromString(tripId)
        assertThat(seatRepository.listByTrip(tripUuid).none { it.seatNumber == 48 }).isTrue()
    }

    @Test
    fun `downsize with overflow SOLD publishes reaccommodation event`() {
        val routeId = createRoute()
        val carrierId = createCarrier()
        val vehicle50 = createVehicle(carrierId, 50)
        val vehicle40 = createVehicle(carrierId, 40)
        val tripId = createPlannedTrip(routeId, vehicle50)

        openTrip(tripId, vehicle50)
        waitForOutbox()
        sellTicket(tripId, seatNumber = 45)

        val before = outboxEventRepository.countByEventType("inventory.reaccommodation.required")
        swapVehicle(tripId, vehicle40)
        waitForOutbox()

        assertThat(outboxEventRepository.countByEventType("inventory.reaccommodation.required"))
            .isEqualTo(before + 1)
    }

    private fun openTrip(tripId: String, vehicleId: String) {
        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"OPEN","vehicleId":"$vehicleId"}"""
        }.andExpect { status { isOk() } }
    }

    private fun swapVehicle(tripId: String, vehicleId: String) {
        mockMvc.patch("/api/trips/$tripId") {
            header(HttpHeaders.AUTHORIZATION, dispatcherAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"vehicleId":"$vehicleId"}"""
        }.andExpect { status { isOk() } }
    }

    private fun reserveSeat(tripId: String, seatNumber: Int): String {
        val response = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":$seatNumber}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun sellTicket(tripId: String, seatNumber: Int) {
        val reservationId = reserveSeat(tripId, seatNumber)
        val shiftResponse = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val shiftId = extractJsonField(shiftResponse, "id")

        mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$shiftId",
                  "passengerName":"Reaccommodation Test",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Reacc Carrier",
                  "legalName": "Reacc Carrier LLC",
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
                  "name": "Reacc Route",
                  "code": "RAC-${System.nanoTime()}",
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
                  "name": "Layout-$seats-${System.nanoTime()}",
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
                  "plateNumber": "RAC-${seats}-${System.nanoTime()}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": $seats
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createPlannedTrip(routeId: String, vehicleId: String): String {
        val tripDate = LocalDate.now().plusDays(3)
        val response = mockMvc.post("/api/trips/from-route") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "tripNumber": "${System.nanoTime()}",
                  "departureTime": "08:30",
                  "vehicleId": "$vehicleId",
                  "openSales": false
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
