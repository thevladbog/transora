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
class CatalogAdminIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `creates point nomenclature and tariff profile with matrix`() {
        val point1 = mockMvc.post("/api/admin/points") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "TST-A",
                  "name": "Test Stop A",
                  "city": "Moscow",
                  "latitude": 55.75,
                  "longitude": 37.61,
                  "isActive": true
                }
            """.trimIndent()
        }.andReturn().response.contentAsString

        val point2 = mockMvc.post("/api/admin/points") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "TST-B",
                  "name": "Test Stop B",
                  "city": "Moscow",
                  "latitude": 55.76,
                  "longitude": 37.62,
                  "isActive": true
                }
            """.trimIndent()
        }.andReturn().response.contentAsString

        val point1Id = extractJsonField(point1, "id")
        val point2Id = extractJsonField(point2, "id")

        mockMvc.get("/api/admin/points") {
            header("Authorization", authHeader)
        }.andExpect {
            status { isOk() }
        }

        val policyBody = mockMvc.post("/api/admin/refund-policies") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Catalog test policy",
                  "serviceFeeCents": 100,
                  "isActive": true,
                  "tiers": [
                    {
                      "hoursBeforeMin": 24,
                      "penaltyPercent": 0,
                      "refundAllowed": true,
                      "sortOrder": 1
                    }
                  ]
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val policyId = extractJsonField(policyBody, "id")

        mockMvc.post("/api/admin/nomenclature") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "BAG-TEST",
                  "name": "Baggage test",
                  "category": "BAGGAGE",
                  "priceCents": 50000,
                  "refundPolicyId": "$policyId",
                  "isActive": true,
                  "saleMode": "TICKET_ATTACHED",
                  "refundAllowed": true,
                  "printName": "Багаж тест",
                  "ffdPaymentObject": 4,
                  "ffdPaymentMethod": 4,
                  "ffdVatTag": 11,
                  "ffdMeasureCode": 796
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.saleMode") { value("TICKET_ATTACHED") }
            jsonPath("$.refundAllowed") { value(true) }
            jsonPath("$.ffdVatTag") { value(11) }
        }

        val profileBody = mockMvc.post("/api/admin/tariff-profiles") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Matrix test profile",
                  "refundPolicyId": "$policyId",
                  "isActive": true
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        val profileId = extractJsonField(profileBody, "id")

        mockMvc.put("/api/admin/tariff-profiles/$profileId/stops") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                { "pointIds": ["$point1Id", "$point2Id"] }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        mockMvc.put("/api/admin/tariff-profiles/$profileId/matrix") {
            header("Authorization", authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "cells": [
                    {
                      "fromStopOrder": 1,
                      "toStopOrder": 2,
                      "priceCents": 150000,
                      "isMirrorOverride": false
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/api/admin/tariff-profiles/$profileId/matrix") {
            header("Authorization", authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.cells.length()").value(1)
        }
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Field $field not found in $json")
    }
}
