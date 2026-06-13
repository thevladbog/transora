package ru.transora.app.sales

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.inventory.ReservationService
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.sales.domain.Ticket
import ru.transora.sales.domain.TicketStatus
import java.time.Clock
import java.util.UUID

@Service
class TicketService(
    private val reservationService: ReservationService,
    private val shiftRepository: ShiftRepository,
    private val ticketRepository: TicketRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    @Transactional
    fun issue(request: IssueTicketRequest): Ticket {
        val shiftId = requireNotNull(request.shiftId)
        shiftRepository.findOpenByIdForUpdate(shiftId)
            ?: throw NoSuchElementException("Open shift $shiftId was not found")

        val reservation = reservationService.consume(requireNotNull(request.reservationId))
        val ticket = Ticket(
            id = UUID.randomUUID(),
            reservationId = reservation.id,
            shiftId = shiftId,
            tripId = reservation.tripId,
            seatNumber = reservation.seatNumber,
            passengerName = request.passengerName.trim(),
            priceCents = request.priceCents,
            status = TicketStatus.ISSUED,
            issuedAt = Clock.systemUTC().instant(),
        )

        ticketRepository.insert(ticket)
        outboxEventRepository.append(
            aggregateType = "ticket",
            aggregateId = ticket.id.toString(),
            eventType = "ticket.issued",
            payload = mapOf(
                "ticketId" to ticket.id,
                "reservationId" to ticket.reservationId,
                "shiftId" to ticket.shiftId,
                "tripId" to ticket.tripId,
                "seatNumber" to ticket.seatNumber,
                "passengerName" to ticket.passengerName,
                "priceCents" to ticket.priceCents,
                "issuedAt" to ticket.issuedAt,
            ),
        )

        return ticket
    }
}
