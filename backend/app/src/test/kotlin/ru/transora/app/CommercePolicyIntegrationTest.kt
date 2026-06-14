package ru.transora.app

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.test.TestAuth
import java.time.LocalDate

@AutoConfigureMockMvc
class CommercePolicyIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var adminAuth: String
    private lateinit var cashierAuth: String
    private val t1StationId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        cashierAuth = TestAuth.bearer(TestAuth.loginAsCashier(mockMvc))
    }

    @Test
    fun `creates commerce policy with pricing fields`() {
        mockMvc.post("/api/admin/policies") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Fixed commission",
                  "policyType": "REFUND",
                  "pricingMode": "FIXED",
                  "fixedPriceCents": 10000,
                  "isActive": true,
                  "tiers": [
                    {
                      "hoursBeforeMin": 0,
                      "penaltyPercent": 0,
                      "refundAllowed": true,
                      "sortOrder": 1
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.policyType") { value("REFUND") }
            jsonPath("$.pricingMode") { value("FIXED") }
            jsonPath("$.fixedPriceCents") { value(10000) }
        }
    }

    @Test
    fun `route policies drive applicable mandatory sale policies`() {
        val nomenclatureId = createNomenclature()
        val salePolicyId = createSalePolicy(nomenclatureId)
        val routeId = createRoute()
        mockMvc.put("/api/admin/routes/$routeId/policies") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "policies": [
                    { "policyId": "$salePolicyId", "priority": 1 }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.policies.length()") { value(1) }
        }

        val tripNumber = "CP${System.nanoTime()}"
        createTariff(tripNumber)
        val tripId = createPlannedTrip(routeId, tripNumber)
        mockMvc.get("/api/sales/applicable-policies?tripId=$tripId&fromStop=1&toStop=2") {
            header(HttpHeaders.AUTHORIZATION, cashierAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.mandatory.length()") { value(1) }
            jsonPath("$.mandatory[0].nomenclatureItemId") { value(nomenclatureId) }
            jsonPath("$.mandatory[0].unitPriceCents") { value(5000) }
        }
    }

    private fun createNomenclature(): String {
        val response = mockMvc.post("/api/admin/nomenclature") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "EARLY-${System.nanoTime()}",
                  "name": "Early booking",
                  "category": "SERVICE",
                  "priceCents": 5000,
                  "isActive": true,
                  "saleMode": "STANDALONE",
                  "pricingMode": "FIXED",
                  "printName": "Early booking"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createSalePolicy(nomenclatureId: String): String {
        val response = mockMvc.post("/api/admin/policies") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Mandatory early booking",
                  "policyType": "SALE",
                  "nomenclatureItemId": "$nomenclatureId",
                  "isMandatory": true,
                  "pricingMode": "FROM_NOMENCLATURE",
                  "isActive": true,
                  "tiers": [
                    {
                      "hoursBeforeMin": 0,
                      "penaltyPercent": 0,
                      "refundAllowed": true,
                      "sortOrder": 1
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Policy Test Carrier",
                  "legalName": "Policy Test Carrier LLC",
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
                  "name": "Policy Route",
                  "code": "PR-${System.nanoTime()}",
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

    private fun createTariff(routeNumber: String) {
        mockMvc.post("/api/admin/tariffs") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "$routeNumber",
                  "fromStopOrder": 1,
                  "toStopOrder": 2,
                  "priceCents": 150000
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
    }

    private fun createPlannedTrip(routeId: String, tripNumber: String): String {
        val tripDate = LocalDate.now().plusDays(5)
        val vehicleId = createVehicle()
        val response = mockMvc.post("/api/trips/from-route") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "tripNumber": "$tripNumber",
                  "departureTime": "08:30",
                  "vehicleId": "$vehicleId",
                  "openSales": true
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createVehicle(): String {
        val carrierId = createCarrier()
        val layoutResponse = mockMvc.post("/api/seat-layouts") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Layout-40",
                  "totalSeats": 40,
                  "layoutJson": "{\"rows\":[]}"
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val layoutId = extractJsonField(layoutResponse, "id")
        val response = mockMvc.post("/api/vehicles") {
            header(HttpHeaders.AUTHORIZATION, adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "model": "Test Bus",
                  "plateNumber": "TST-${System.nanoTime()}",
                  "seatLayoutId": "$layoutId",
                  "totalSeats": 40
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }
}
