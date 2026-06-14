package ru.transora.app

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.test.TestAuth

@AutoConfigureMockMvc
class RoutePricingIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var adminAuth: String

    @BeforeEach
    fun authenticate() {
        adminAuth = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `creates route with stops matrix and distance`() {
        val carrierId = createCarrier()
        val point1 = createPoint("RP-A", "Stop A")
        val point2 = createPoint("RP-B", "Stop B")

        val createBody = mockMvc.post("/api/admin/route-pricing") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "code": "RP-TEST",
                  "routeNumber": "901",
                  "name": "Route pricing test"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.routeNumber").value("901")
            jsonPath("$.code").value("RP-TEST")
        }.andReturn().response.contentAsString

        val routeId = extractJsonField(createBody, "routeId")

        mockMvc.put("/api/admin/route-pricing/$routeId/stops") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "pointIds": ["$point1", "$point2"], "legDurationsMin": [90] }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.stops.length()").value(2)
        }

        mockMvc.put("/api/admin/route-pricing/$routeId/matrix") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "cells": [
                    {
                      "fromStopOrder": 1,
                      "toStopOrder": 2,
                      "priceCents": 175000,
                      "isMirrorOverride": false
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.cells[0].priceCents").value(175000)
        }

        mockMvc.get("/api/admin/route-pricing/$routeId") {
            header("Authorization", adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.distanceKm").isNumber()
            jsonPath("$.distanceSource").exists()
        }

        mockMvc.get("/api/admin/route-pricing") {
            header("Authorization", adminAuth)
        }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.routeId == '$routeId')].stopCount").value(2)
            jsonPath("$[?(@.routeId == '$routeId')].carrierName").value("RP Carrier")
        }
    }

    @Test
    fun `rejects invalid validFrom validTo range`() {
        val carrierId = createCarrier()
        val createBody = mockMvc.post("/api/admin/route-pricing") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "code": "RP-INV",
                  "routeNumber": "902",
                  "name": "Invalid range test"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString

        val routeId = extractJsonField(createBody, "routeId")

        mockMvc.put("/api/admin/route-pricing/$routeId") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "validFrom": "2026-06-19", "validTo": "2026-06-03" }
            """.trimIndent()
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `from-route defaults tripNumber to route code when routeNumber omitted`() {
        val carrierId = createCarrier()
        val point1 = createPoint("RP-T1", "Trip Stop A")
        val point2 = createPoint("RP-T2", "Trip Stop B")

        val createBody = mockMvc.post("/api/admin/route-pricing") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "carrierId": "$carrierId",
                  "code": "RP-TRIP",
                  "name": "Trip default test"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.routeNumber").value("RP-TRIP")
        }.andReturn().response.contentAsString

        val routeId = extractJsonField(createBody, "routeId")

        mockMvc.put("/api/admin/route-pricing/$routeId/stops") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "pointIds": ["$point1", "$point2"], "legDurationsMin": [60] }
            """.trimIndent()
        }.andExpect { status { isOk() } }

        val tripDate = java.time.LocalDate.now().plusDays(7)
        mockMvc.post("/api/trips/from-route") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeId": "$routeId",
                  "tripDate": "$tripDate",
                  "departureTime": "09:00"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.tripNumber").value("RP-TRIP")
            jsonPath("$.routeNumber").value("RP-TRIP")
        }
    }

    private fun createCarrier(): String {
        val inn = "${System.nanoTime()}".takeLast(10).padStart(10, '0')
        val response = mockMvc.post("/api/carriers") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "RP Carrier",
                  "legalName": "RP Carrier LLC",
                  "inn": "$inn",
                  "contractType": "ROUTE_RENT"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun createPoint(code: String, name: String): String {
        val response = mockMvc.post("/api/admin/points") {
            header("Authorization", adminAuth)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "$code-${System.nanoTime()}",
                  "name": "$name",
                  "city": "Moscow",
                  "latitude": 55.75,
                  "longitude": 37.61,
                  "isActive": true
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun extractJsonField(json: String, field: String): String =
        Regex(""""$field"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: error("$field missing in $json")
}
