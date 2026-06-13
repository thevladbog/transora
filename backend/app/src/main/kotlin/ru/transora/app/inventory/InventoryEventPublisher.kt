package ru.transora.app.inventory

import org.springframework.stereotype.Component
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.sales.TicketRepository
import ru.transora.sales.domain.TicketStatus
import java.time.Clock
import java.util.UUID

@Component
class InventoryEventPublisher(
    private val outboxEventRepository: OutboxEventRepository,
    private val ticketRepository: TicketRepository,
) {
    fun publishTransitGateOpened(
        gateId: UUID,
        tripId: UUID,
        stationId: UUID,
        stopOrder: Int,
        availableSeats: List<Int>,
    ) {
        publish(
            eventType = "inventory.transit_gate.opened",
            aggregateType = "transit_gate",
            aggregateId = gateId.toString(),
            payload = mapOf(
                "gateId" to gateId.toString(),
                "tripId" to tripId.toString(),
                "stationId" to stationId.toString(),
                "stopOrder" to stopOrder,
                "availableSeats" to availableSeats,
            ),
        )
    }

    fun publishTransitGateClosed(
        gateId: UUID,
        tripId: UUID,
        stationId: UUID,
        stopOrder: Int,
    ) {
        publish(
            eventType = "inventory.transit_gate.closed",
            aggregateType = "transit_gate",
            aggregateId = gateId.toString(),
            payload = mapOf(
                "gateId" to gateId.toString(),
                "tripId" to tripId.toString(),
                "stationId" to stationId.toString(),
                "stopOrder" to stopOrder,
            ),
        )
    }

    fun publishReaccommodationRequired(
        tripId: UUID,
        seatNumbers: List<Int>,
    ) {
        val ticketIds = ticketRepository.listByTripId(tripId)
            .filter { it.status == TicketStatus.ISSUED && it.seatNumber in seatNumbers }
            .map { it.id.toString() }
        publish(
            eventType = "inventory.reaccommodation.required",
            aggregateType = "trip_inventory",
            aggregateId = tripId.toString(),
            payload = mapOf(
                "tripId" to tripId.toString(),
                "seatNumbers" to seatNumbers,
                "ticketIds" to ticketIds,
            ),
        )
    }

    private fun publish(eventType: String, aggregateType: String, aggregateId: String, payload: Map<String, Any?>) {
        val envelope = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "event_type" to eventType,
            "occurred_at" to Clock.systemUTC().instant().toString(),
            "payload" to payload,
        )
        outboxEventRepository.append(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            eventType = eventType,
            payload = envelope,
        )
    }
}
