package ru.transora.app.sales

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.documents.PrintContext
import ru.transora.app.documents.TicketDocumentService
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.iam.security.currentUserId
import ru.transora.iam.permissions.Permissions
import ru.transora.sales.domain.Ticket
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/tickets")
@Tag(name = "Tickets", description = "Ticket sales")
class TicketController(
    private val ticketService: TicketService,
    private val refundService: RefundService,
    private val ticketDocumentService: TicketDocumentService,
) {
    @PostMapping
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "Issue a ticket from an active reservation via unified sale saga (fiscal + payment)")
    fun issue(@Valid @RequestBody request: IssueTicketRequestBody): TicketResponse =
        ticketService.issue(request.toServiceRequest()).toResponse()

    @PostMapping("/{ticketId}/refund")
    @RequirePermission(Permissions.TICKETS_REFUND)
    @Operation(summary = "Refund a ticket using PP RF 112 penalty tiers")
    fun refund(@PathVariable ticketId: UUID): RefundResponse =
        refundService.refundTicket(ticketId).toResponse()

    @GetMapping("/{ticketId}")
    @RequirePermission(Permissions.TICKETS_VIEW)
    @Operation(summary = "Get ticket by id")
    fun get(@PathVariable ticketId: UUID): TicketResponse =
        ticketService.getById(ticketId).toResponse()

    @GetMapping
    @RequirePermission(Permissions.TICKETS_VIEW)
    @Operation(summary = "List tickets by trip id")
    fun list(@RequestParam tripId: UUID): List<TicketResponse> =
        ticketService.listByTripId(tripId).map { it.toResponse() }

    @GetMapping("/{ticketId}/document")
    @RequirePermission(Permissions.DOCUMENTS_PRINT)
    @Operation(summary = "Download ticket PDF document; logs each print to print_log")
    fun document(
        @PathVariable ticketId: UUID,
        @RequestParam(required = false) stationCode: String?,
        @RequestParam(required = false) posId: String?,
    ): ResponseEntity<ByteArray> {
        val ticket = ticketService.getById(ticketId)
        val document = ticketDocumentService.getOrGenerate(ticketId)
        val documentId = document.documentId
            ?: throw IllegalStateException("Generated document metadata missing for ticket $ticketId")
        ticketDocumentService.logPrint(
            ticketId = ticketId,
            documentId = documentId,
            tripId = ticket.tripId,
            context = PrintContext(
                printedBy = currentUserId().toString(),
                stationCode = stationCode,
                posId = posId,
            ),
        )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-${document.ticketId}.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(document.content)
    }
}

data class IssueTicketRequestBody(
    @field:NotNull val reservationId: UUID?,
    @field:NotNull val shiftId: UUID?,
    @field:NotBlank val passengerName: String,
    @field:NotBlank val docType: String,
    @field:NotBlank val docNumber: String,
    val paymentType: String = "CASH",
) {
    fun toServiceRequest(): IssueTicketRequest =
        IssueTicketRequest(
            reservationId = reservationId,
            shiftId = shiftId,
            passengerName = passengerName,
            docType = docType,
            docNumber = docNumber,
            paymentType = paymentType,
        )
}

data class RefundResponse(
    val refundId: String,
    val ticketId: String,
    val penaltyPercent: String,
    val penaltyCents: Long,
    val serviceFeeCents: Long,
    val refundCents: Long,
    val refundType: String,
)

private fun RefundResult.toResponse(): RefundResponse =
    RefundResponse(
        refundId = refund.id.toString(),
        ticketId = refund.ticketId.toString(),
        penaltyPercent = refund.penaltyPercent.toPlainString(),
        penaltyCents = refund.penaltyCents,
        serviceFeeCents = refund.serviceFeeCents,
        refundCents = refund.refundCents,
        refundType = refund.refundType.name,
    )

data class TicketResponse(
    val id: String,
    val ticketNumber: String,
    val reservationId: String,
    val shiftId: String,
    val tripId: String,
    val seatNumber: Int,
    val passengerName: String,
    val priceCents: Long,
    val status: String,
    val issuedAt: Instant,
    val orderId: String? = null,
)

private fun Ticket.toResponse(): TicketResponse =
    TicketResponse(
        id = id.toString(),
        ticketNumber = ticketNumber,
        reservationId = reservationId.toString(),
        shiftId = shiftId.toString(),
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        passengerName = passengerName,
        priceCents = priceCents,
        status = status.name,
        issuedAt = issuedAt,
        orderId = orderId?.toString(),
    )
