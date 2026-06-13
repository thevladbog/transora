package ru.transora.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.sales.FiscalReceiptRepository
import ru.transora.app.sales.OrderRepository
import ru.transora.app.sales.RefundRepository
import ru.transora.app.test.TestAuth
import ru.transora.sales.domain.FiscalReceiptType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class FiscalPersistenceIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var refundRepository: RefundRepository

    @Autowired
    private lateinit var fiscalReceiptRepository: FiscalReceiptRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `order sale persists fiscal receipt and links order`() {
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        val orderJson = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 5,
                  "passengerName":"Anna Smirnova",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PAID") }
        }.andReturn().response.contentAsString

        val orderId = UUID.fromString(extractJsonField(orderJson, "orderId"))
        val fiscalReceiptId = orderRepository.findFiscalReceiptId(orderId)
        assertThat(fiscalReceiptId).isNotNull

        val saleCount = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.fiscal_receipts
            WHERE order_id = ? AND receipt_type = ?
            """.trimIndent(),
            Int::class.java,
            orderId,
            FiscalReceiptType.SALE.name,
        )
        assertThat(saleCount).isEqualTo(1)

        val receipt = fiscalReceiptRepository.findByShiftAndType(
            UUID.fromString(shiftId),
            FiscalReceiptType.SALE,
        )
        assertThat(receipt).isNotNull
        assertThat(receipt!!.orderId).isEqualTo(orderId)
        assertThat(receipt.fiscalSign).isNotBlank
        assertThat(receipt.fiscalDocNo).isPositive
    }

    @Test
    fun `ticket refund persists fiscal receipt and links refund`() {
        val tripId = createTrip()
        waitForOutbox()
        val shiftId = openShift()

        val orderJson = mockMvc.post("/api/orders") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "shiftId":"$shiftId",
                  "tripId":"$tripId",
                  "seatNumber": 6,
                  "passengerName":"Ivan Petrov",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 654321",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val ticketId = extractJsonField(orderJson, "ticketId")
        waitForOutbox()

        val refundJson = mockMvc.post("/api/tickets/$ticketId/refund") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect {
            status { isOk() }
            jsonPath("$.refundId") { exists() }
        }.andReturn().response.contentAsString

        val refundId = UUID.fromString(extractJsonField(refundJson, "refundId"))
        val fiscalReceiptId = refundRepository.findFiscalReceiptId(refundId)
        assertThat(fiscalReceiptId).isNotNull

        val refundCount = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.fiscal_receipts
            WHERE refund_id = ? AND receipt_type = ?
            """.trimIndent(),
            Int::class.java,
            refundId,
            FiscalReceiptType.REFUND.name,
        )
        assertThat(refundCount).isEqualTo(1)
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
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun openShift(): String {
        val response = mockMvc.post("/api/shifts") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"stationName":"Transora Central","cashierName":"cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun extractJsonField(json: String, field: String): String {
        val stringPattern = """"$field"\s*:\s*"([^"]+)"""".toRegex()
        stringPattern.find(json)?.groupValues?.get(1)?.let { return it }
        val numberPattern = """"$field"\s*:\s*(\d+)""".toRegex()
        return numberPattern.find(json)?.groupValues?.get(1)
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
