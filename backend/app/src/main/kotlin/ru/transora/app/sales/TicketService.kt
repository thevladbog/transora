package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.sales.domain.Ticket
import java.util.UUID

@Service
class TicketService(
    private val ticketSaleOrchestrator: TicketSaleOrchestrator,
    private val ticketRepository: TicketRepository,
) {
    @Transactional
    fun issue(request: IssueTicketRequest): Ticket {
        val result = ticketSaleOrchestrator.completeFromReservation(
            shiftId = requireNotNull(request.shiftId),
            reservationId = requireNotNull(request.reservationId),
            passengerName = request.passengerName,
            docType = request.docType,
            docNumber = request.docNumber,
            paymentType = request.paymentType,
        )
        return result.ticket
    }

    fun getById(id: UUID): Ticket =
        ticketRepository.findById(id) ?: throw NoSuchElementException("Ticket $id was not found")

    fun listByTripId(tripId: UUID): List<Ticket> =
        ticketRepository.listByTripId(tripId)
}

data class IssueTicketRequest(
    val reservationId: UUID?,
    val shiftId: UUID?,
    val passengerName: String,
    val docType: String,
    val docNumber: String,
    val paymentType: String = "CASH",
)
