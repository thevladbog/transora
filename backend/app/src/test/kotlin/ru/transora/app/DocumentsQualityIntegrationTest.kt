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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.transora.app.documents.DocumentBarcodes
import ru.transora.app.documents.PrintType
import ru.transora.app.documents.TicketQrPayload
import ru.transora.app.outbox.OutboxPublisher
import ru.transora.app.sales.TicketRepository
import ru.transora.app.test.TestAuth
import ru.transora.sales.domain.Ticket
import ru.transora.sales.domain.TicketStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
class DocumentsQualityIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var ticketRepository: TicketRepository

    @Autowired
    private lateinit var outboxPublisher: OutboxPublisher

    private lateinit var authHeader: String

    @BeforeEach
    fun authenticate() {
        authHeader = TestAuth.bearer(TestAuth.loginAsAdmin(mockMvc))
    }

    @Test
    fun `ticket qr payload includes valid checksum`() {
        val ticketId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val tripId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ticket = sampleTicket(ticketId, tripId, seatNumber = 12)

        val payload = TicketQrPayload.build(ticket)
        val expectedCs = TicketQrPayload.crc32Short("${ticketId}${tripId}12")

        assertThat(payload).isEqualTo(
            """{"v":1,"tid":"$ticketId","trip":"$tripId","seat":12,"cs":"$expectedCs"}""",
        )
    }

    @Test
    fun `ticket document download writes print log with reprint on second request`() {
        val ticketId = issueTicket(seatNumber = 3)
        waitForOutbox()

        mockMvc.get("/api/tickets/$ticketId/document") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            param("stationCode", "T1")
            param("posId", "POS-DOC")
        }.andExpect {
            status { isOk() }
            content { contentType("application/pdf") }
        }

        assertThat(printLogCount(ticketId)).isEqualTo(1)
        assertThat(latestPrintType(ticketId)).isEqualTo(PrintType.TICKET_PRINT.name)
        assertThat(latestPrintStationCode(ticketId)).isEqualTo("T1")
        assertThat(latestPrintPosId(ticketId)).isEqualTo("POS-DOC")

        mockMvc.get("/api/tickets/$ticketId/document") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }

        assertThat(printLogCount(ticketId)).isEqualTo(2)
        assertThat(latestPrintType(ticketId)).isEqualTo(PrintType.TICKET_REPRINT.name)
    }

    @Test
    fun `manifest excludes refunded tickets`() {
        val tripId = createTrip()
        waitForOutbox()
        val activeTicketId = issueTicket(tripId, seatNumber = 5, passengerName = "Active Passenger")
        val refundedTicketId = issueTicket(tripId, seatNumber = 6, passengerName = "Refunded Passenger")
        waitForOutbox()

        mockMvc.post("/api/tickets/$refundedTicketId/refund") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
        waitForOutbox()

        val activeTickets = ticketRepository.listActiveByTripId(UUID.fromString(tripId))
        assertThat(activeTickets).hasSize(1)
        assertThat(activeTickets.single().id).isEqualTo(UUID.fromString(activeTicketId))
        assertThat(activeTickets.single().passengerName).isEqualTo("Active Passenger")

        val manifestResponse = mockMvc.post("/api/trips/$tripId/documents/manifest") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        assertThat(manifestResponse).contains("\"manifestVersion\":1")
        assertThat(extractJsonField(manifestResponse, "manifestDocumentId")).isNotBlank()
        assertThat(extractJsonField(manifestResponse, "boardingSheetDocumentId")).isNotBlank()
    }

    @Test
    fun `boarding sheet pdf includes barcode images`() {
        val tripId = createTrip()
        waitForOutbox()
        issueTicket(tripId, seatNumber = 8, passengerName = "Boarding Passenger")
        waitForOutbox()

        val manifestResponse = mockMvc.post("/api/trips/$tripId/documents/manifest") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        val manifestDocId = extractJsonField(manifestResponse, "manifestDocumentId")
        val boardingDocId = extractJsonField(manifestResponse, "boardingSheetDocumentId")

        val manifestPdf = mockMvc.get("/api/documents/$manifestDocId") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andReturn().response.contentAsByteArray

        val boardingPdf = mockMvc.get("/api/documents/$boardingDocId") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andReturn().response.contentAsByteArray

        assertThat(boardingPdf.size).isGreaterThan(manifestPdf.size)
        assertThat(String(boardingPdf, Charsets.ISO_8859_1)).contains("/Image")
    }

    @Test
    fun `thermal ticket pdf uses 80mm page width`() {
        val ticketId = issueTicket(seatNumber = 11)
        waitForOutbox()

        val pdf = mockMvc.get("/api/tickets/$ticketId/document") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsByteArray

        val pdfText = String(pdf, Charsets.ISO_8859_1)
        val widthMatch = Regex("""/MediaBox\s*\[\s*0\s+0\s+([\d.]+)\s+""").find(pdfText)
        assertThat(widthMatch).isNotNull
        val pageWidth = widthMatch!!.groupValues[1].toFloat()
        assertThat(pageWidth).isBetween(DocumentBarcodes.THERMAL_WIDTH_PT - 1f, DocumentBarcodes.THERMAL_WIDTH_PT + 1f)
    }

    private fun sampleTicket(id: UUID, tripId: UUID, seatNumber: Int): Ticket =
        Ticket(
            id = id,
            ticketNumber = "250010000001",
            reservationId = UUID.randomUUID(),
            shiftId = UUID.randomUUID(),
            tripId = tripId,
            seatNumber = seatNumber,
            passengerName = "Test Passenger",
            priceCents = 150000,
            status = TicketStatus.ISSUED,
            issuedAt = Instant.now(),
        )

    private fun issueTicket(
        tripId: String = createTrip(),
        seatNumber: Int,
        passengerName: String = "Doc Test Passenger",
    ): String {
        waitForOutbox()
        val reservationResponse = mockMvc.post("/api/reservations") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """{"tripId":"$tripId","seatNumber":$seatNumber}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val reservationId = extractJsonField(reservationResponse, "id")

        val shiftId = openShift()
        waitForOutbox()

        val ticketResponse = mockMvc.post("/api/tickets") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "reservationId":"$reservationId",
                  "shiftId":"$shiftId",
                  "passengerName":"$passengerName",
                  "docType":"PASSPORT_RF",
                  "docNumber":"4510 123456",
                  "paymentType":"CASH"
                }
            """.trimIndent()
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return extractJsonField(ticketResponse, "id")
    }

    private fun createTrip(): String {
        val departureTime = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val response = mockMvc.post("/api/trips") {
            header(HttpHeaders.AUTHORIZATION, authHeader)
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "routeNumber": "DOC-${System.nanoTime()}",
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
            content = """{"stationName":"Transora Central","cashierName":"doc-cashier-${System.nanoTime()}"}"""
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return extractJsonField(response, "id")
    }

    private fun printLogCount(ticketId: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM documents.print_log WHERE ticket_id = ?",
            Int::class.java,
            UUID.fromString(ticketId),
        ) ?: 0

    private fun latestPrintType(ticketId: String): String =
        jdbc.queryForObject(
            """
            SELECT print_type FROM documents.print_log
            WHERE ticket_id = ?
            ORDER BY printed_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            UUID.fromString(ticketId),
        ) ?: error("print_log missing for ticket $ticketId")

    private fun latestPrintStationCode(ticketId: String): String? =
        jdbc.queryForObject(
            """
            SELECT station_code FROM documents.print_log
            WHERE ticket_id = ?
            ORDER BY printed_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            UUID.fromString(ticketId),
        )

    private fun latestPrintPosId(ticketId: String): String? =
        jdbc.queryForObject(
            """
            SELECT pos_id FROM documents.print_log
            WHERE ticket_id = ?
            ORDER BY printed_at DESC
            LIMIT 1
            """.trimIndent(),
            String::class.java,
            UUID.fromString(ticketId),
        )

    private fun waitForOutbox() {
        outboxPublisher.publishPendingEvents()
        TimeUnit.MILLISECONDS.sleep(200)
        outboxPublisher.publishPendingEvents()
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = """"$field"\s*:\s*"([^"]+)""""
        return Regex(pattern).find(json)?.groupValues?.get(1)
            ?: error("Field $field not found in: $json")
    }
}
