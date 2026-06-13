package ru.transora.app.stationagent

import org.springframework.stereotype.Component
import ru.transora.app.sales.TicketRepository
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.app.scheduling.TripRepository
import java.time.Instant
import java.util.UUID

@Component
class StationAgentTicketPushService(
    private val ticketRepository: TicketRepository,
    private val tripRepository: TripRepository,
    private val serviceStationRepository: ServiceStationRepository,
    private val stationAgentEventPublisher: StationAgentEventPublisher,
) {
    fun pushTicketEvent(ticketId: UUID, eventType: String, eventAt: Instant) {
        val ticket = ticketRepository.findById(ticketId) ?: return
        val trip = tripRepository.findById(ticket.tripId) ?: return
        val station = serviceStationRepository.findByCode(trip.departureStationCode.trim().uppercase()) ?: return
        stationAgentEventPublisher.notifyTicketEvent(
            stationId = station.id,
            eventType = eventType,
            payload = TicketStatusPayload(
                ticketId = ticket.id.toString(),
                ticketNumber = ticket.ticketNumber,
                tripId = ticket.tripId.toString(),
                status = ticket.status.name,
                passengerName = ticket.passengerName,
                seatNumber = ticket.seatNumber,
                stationId = station.id.toString(),
                scannedAt = eventAt.toString(),
            ),
        )
    }
}
