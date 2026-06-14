package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.hardware.FiscalReceiptRequest
import ru.transora.app.hardware.FiscalReceiptResult
import ru.transora.app.hardware.FiscalShiftOpenRequest
import ru.transora.app.hardware.FiscalShiftOpenResult
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.hardware.PrintTicketRequest
import ru.transora.app.hardware.PrintTicketResult
import ru.transora.app.hardware.ShiftZReportRequest
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.sales.OrderNomenclatureRepository
import ru.transora.app.test.TestAuth
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@AutoConfigureMockMvc
@Import(CapturingFiscalConfiguration::class)
class NomenclatureSaleIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var orderNomenclatureRepository: OrderNomenclatureRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        CapturingFiscalConfiguration.saleReceipts.clear()
        CapturingFiscalConfiguration.refundReceipts.clear()
    }

    @Test
    fun `order with nomenclature addons persists lines and fiscal payload`() {
        val policyId = createRefundPolicy()
        val baggageId = createNomenclature(
            code = "BAG-SALE",
            saleMode = "TICKET_ATTACHED",
            refundAllowed = true,
            policyId = policyId,
            priceCents = 50000,
            printName = "Багаж доп.",
            vatTag = 11,
        )
        val insuranceId = createNomenclature(
            code = "INS-STAND",
            saleMode = "STANDALONE",
            refundAllowed = false,
            priceCents = 30000,
            printName = "Страховка",
            vatTag = 6,
        )

        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 4,
                  "passengerName":"Anna Smirnova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH",
                  "nomenclatureAddons": [
                    { "nomenclatureItemId": "$baggageId", "quantity": 1 }
                  ],
                  "standaloneNomenclature": [
                    { "nomenclatureItemId": "$insuranceId", "quantity": 1 }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCents") { exists() }
        }

        val orderId = jdbc.queryForObject(
            "SELECT id FROM sales.orders ORDER BY created_at DESC LIMIT 1",
            UUID::class.java,
        )!!
        val lines = orderNomenclatureRepository.findByOrderId(orderId)
        assertThat(lines).hasSize(2)
        assertThat(lines.map { it.nomenclatureItemId }).containsExactlyInAnyOrder(
            UUID.fromString(baggageId),
            UUID.fromString(insuranceId),
        )

        val fiscal = CapturingFiscalConfiguration.saleReceipts.single()
        assertThat(fiscal.lines).hasSize(3)
        assertThat(fiscal.lines.map { it.printName }).contains("Багаж доп.", "Страховка")
    }

    @Test
    fun `catalog and quote return prices for trip segment`() {
        createNomenclature(
            code = "BAG-CAT-${System.nanoTime()}",
            saleMode = "STANDALONE",
            priceCents = 25000,
        )
        val itemCode = jdbc.queryForObject(
            "SELECT code FROM sales.nomenclature_items ORDER BY created_at DESC LIMIT 1",
            String::class.java,
        )!!
        val tripId = createTrip()
        waitForOutbox()

        val catalogBody = mockMvc.get("/api/nomenclature/catalog?tripId=$tripId&fromStop=1&toStop=2") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        assertThat(catalogBody).contains(itemCode)

        val itemId = jdbc.queryForObject(
            "SELECT id FROM sales.nomenclature_items WHERE code = ?",
            UUID::class.java,
            itemCode,
        )!!

        mockMvc.post("/api/nomenclature/quote") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "tripId":"$tripId",
                  "fromStopOrder": 1,
                  "toStopOrder": 2,
                  "items": [
                    { "nomenclatureItemId":"$itemId", "quantity": 2, "saleMode": "STANDALONE" }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCents") { value(50000) }
        }
    }

    private fun createRefundPolicy(): String {
        val body = mockMvc.post("/api/admin/refund-policies") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Nomenclature refund policy",
                  "serviceFeeCents": 0,
                  "isActive": true,
                  "tiers": [
                    { "hoursBeforeMin": 0, "penaltyPercent": 0, "refundAllowed": true, "sortOrder": 1 }
                  ]
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(body, "id")
    }

    private fun createNomenclature(
        code: String,
        saleMode: String,
        priceCents: Long,
        printName: String = code,
        vatTag: Int = 6,
        refundAllowed: Boolean = false,
        policyId: String? = null,
    ): String {
        val policyField = if (policyId != null) ""","refundPolicyId":"$policyId"""" else ""
        val body = mockMvc.post("/api/admin/nomenclature") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "$code",
                  "name": "$code",
                  "category": "BAGGAGE",
                  "priceCents": $priceCents,
                  "isActive": true,
                  "saleMode": "$saleMode",
                  "pricingMode": "FIXED",
                  "refundAllowed": $refundAllowed,
                  "printName": "$printName",
                  "ffdPaymentObject": 4,
                  "ffdPaymentMethod": 4,
                  "ffdVatTag": $vatTag,
                  "ffdMeasureCode": 796
                  $policyField
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(body, "id")
    }

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "501",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "3",
                  "seatCount": 45
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openShift(): String {
        val response = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
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
            if (outboxEventRepository.countUnpublished() == 0) return
            outboxPublisher.publishPendingEvents()
            Thread.sleep(200)
        }
        throw AssertionError("Outbox events were not published in time")
    }
}

@AutoConfigureMockMvc
@Import(CapturingFiscalConfiguration::class)
class NomenclatureRefundIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var orderNomenclatureRepository: OrderNomenclatureRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
        CapturingFiscalConfiguration.saleReceipts.clear()
        CapturingFiscalConfiguration.refundReceipts.clear()
    }

    @Test
    fun `nomenclature refund preview and refund with fiscal line`() {
        val policyId = createRefundPolicy()
        val itemId = createNomenclature(policyId)
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 6,
                  "passengerName":"Anna Smirnova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH",
                  "standaloneNomenclature": [
                    { "nomenclatureItemId": "$itemId", "quantity": 1 }
                  ]
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }

        val lineId = orderNomenclatureRepository.findByOrderId(
            jdbc.queryForObject(
                "SELECT id FROM sales.orders ORDER BY created_at DESC LIMIT 1",
                UUID::class.java,
            )!!,
        ).single().id

        mockMvc.get("/api/nomenclature/lines/$lineId/refund-preview") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.refundAllowed") { value(true) }
        }

        mockMvc.post("/api/nomenclature/lines/$lineId/refund") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{ "refundType": "CASH" }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.refundId") { exists() }
        }

        val refund = CapturingFiscalConfiguration.refundReceipts.single()
        assertThat(refund.lines).hasSize(1)
        assertThat(refund.lines.single().vatTag).isEqualTo(11)
    }

    private fun createRefundPolicy(): String {
        val body = mockMvc.post("/api/admin/refund-policies") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "name": "Refund policy",
                  "serviceFeeCents": 0,
                  "isActive": true,
                  "tiers": [
                    { "hoursBeforeMin": 0, "penaltyPercent": 0, "refundAllowed": true, "sortOrder": 1 }
                  ]
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(body, "id")
    }

    private fun createNomenclature(policyId: String): String {
        val body = mockMvc.post("/api/admin/nomenclature") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "REF-BAG",
                  "name": "Refundable bag",
                  "category": "BAGGAGE",
                  "priceCents": 40000,
                  "isActive": true,
                  "saleMode": "STANDALONE",
                  "refundAllowed": true,
                  "refundPolicyId": "$policyId",
                  "printName": "Багаж возвратный",
                  "ffdVatTag": 11
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(body, "id")
    }

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "501",
                  "departureStation": "Transora Central",
                  "arrivalStation": "North Terminal",
                  "departureStationCode": "T1",
                  "departureTime": "$departureTime",
                  "platform": "3",
                  "seatCount": 45
                }
            """.trimIndent()
        }.andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openShift(): String {
        val response = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andReturn().response.contentAsString
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
            if (outboxEventRepository.countUnpublished() == 0) return
            outboxPublisher.publishPendingEvents()
            Thread.sleep(200)
        }
        throw AssertionError("Outbox events were not published in time")
    }
}

@TestConfiguration
class CapturingFiscalConfiguration {
    companion object {
        val saleReceipts = CopyOnWriteArrayList<FiscalReceiptRequest>()
        val refundReceipts = CopyOnWriteArrayList<FiscalReceiptRequest>()
    }

    @Bean
    @Primary
    fun hardwareAgentClient(): HardwareAgentClient = object : HardwareAgentClient {
        override fun openFiscalShift(request: FiscalShiftOpenRequest): FiscalShiftOpenResult =
            FiscalShiftOpenResult(fiscalShiftNo = 1)

        override fun printFiscalReceipt(request: FiscalReceiptRequest): FiscalReceiptResult {
            saleReceipts += request
            return mockResult(request.operationId)
        }

        override fun printFiscalRefund(request: FiscalReceiptRequest): FiscalReceiptResult {
            refundReceipts += request
            return mockResult(request.operationId)
        }

        override fun printZReport(request: ShiftZReportRequest): FiscalReceiptResult =
            mockResult(request.shiftId)

        override fun printTicket(request: PrintTicketRequest): PrintTicketResult =
            PrintTicketResult(ticketId = request.ticketId, printed = true)

        private fun mockResult(operationId: UUID): FiscalReceiptResult =
            FiscalReceiptResult(
                operationId = operationId,
                fiscalSign = Random.nextLong(1_000_000, 9_999_999).toString(),
                fiscalDocNo = Random.nextLong(1, 999_999),
                printedAt = Clock.systemUTC().instant(),
            )
    }
}
