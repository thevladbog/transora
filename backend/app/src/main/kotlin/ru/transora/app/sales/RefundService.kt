package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.hardware.FiscalReceiptRequest
import ru.transora.app.hardware.HardwareAgentClient
import ru.transora.app.inventory.ReservationService
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.scheduling.TripRepository
import ru.transora.sales.domain.Refund
import ru.transora.sales.domain.RefundType
import ru.transora.sales.domain.TicketStatus
import java.time.Clock
import java.util.UUID

@Service
class RefundService(
    private val ticketRepository: TicketRepository,
    private val refundRepository: RefundRepository,
    private val refundCalculator: RefundCalculator,
    private val reservationService: ReservationService,
    private val outboxEventRepository: OutboxEventRepository,
    private val hardwareAgentClient: HardwareAgentClient,
    private val shiftRepository: ShiftRepository,
    private val tripRepository: TripRepository,
    private val fiscalReceiptService: FiscalReceiptService,
    private val commercePolicyResolver: CommercePolicyResolver,
) {
    @Transactional
    fun refundTicket(ticketId: UUID, refundType: RefundType = RefundType.CASH): RefundResult {
        val ticket = ticketRepository.findByIdForUpdate(ticketId)
            ?: throw NoSuchElementException("Ticket $ticketId was not found")

        if (ticket.status != TicketStatus.ISSUED) {
            throw DomainRuleViolation("Ticket $ticketId is not refundable")
        }

        val preview = refundCalculator.preview(ticket.priceCents, ticket.tripId)
        if (!preview.refundAllowed) {
            throw DomainRuleViolation("Refund is not allowed for this ticket under PP RF 112 policy")
        }

        val policyId = preview.policyId
            ?: commercePolicyResolver.resolveTicketRefundPolicyId(ticket.tripId)

        val shift = shiftRepository.findById(ticket.shiftId)
        val trip = tripRepository.findById(ticket.tripId)

        val fiscalResult = runCatching {
            hardwareAgentClient.printFiscalRefund(
                FiscalReceiptRequest(
                    operationId = UUID.randomUUID(),
                    amountCents = preview.refundCents,
                    cashierName = shift?.cashierName ?: "cashier",
                    description = "Refund ticket ${ticket.ticketNumber}",
                ),
            )
        }.getOrElse { ex ->
            throw DomainRuleViolation("Fiscal refund failed: ${ex.message}")
        }

        val now = Clock.systemUTC().instant()
        val refund = Refund(
            id = UUID.randomUUID(),
            ticketId = ticket.id,
            policyId = policyId,
            penaltyPercent = preview.penaltyPercent,
            penaltyCents = preview.penaltyCents,
            serviceFeeCents = preview.serviceFeeCents,
            refundCents = preview.refundCents,
            refundType = refundType,
            createdAt = now,
        )
        refundRepository.insert(refund)
        fiscalReceiptService.recordRefund(
            shiftId = ticket.shiftId,
            refundId = refund.id,
            amountCents = refund.refundCents,
            result = fiscalResult,
        )
        ticketRepository.updateStatus(ticket.id, TicketStatus.REFUNDED)
        reservationService.releaseSeatSale(ticket.id, ticket.tripId, ticket.seatNumber)

        outboxEventRepository.append(
            aggregateType = "ticket",
            aggregateId = ticket.id.toString(),
            eventType = "sales.ticket.refunded",
            payload = mapOf(
                "ticketId" to ticket.id,
                "ticketNumber" to ticket.ticketNumber,
                "tripId" to ticket.tripId,
                "seatNumber" to ticket.seatNumber,
                "refundCents" to refund.refundCents,
                "penaltyCents" to refund.penaltyCents,
                "refundType" to refund.refundType.name,
                "routeNumber" to trip?.routeNumber,
            ),
        )

        return RefundResult(refund = refund, preview = preview)
    }
}

data class RefundResult(
    val refund: Refund,
    val preview: ru.transora.sales.domain.RefundPreview,
)
