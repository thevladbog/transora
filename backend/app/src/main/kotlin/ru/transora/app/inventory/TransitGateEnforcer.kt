package ru.transora.app.inventory

import org.springframework.stereotype.Component
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.scheduling.TripStopRepository
import ru.transora.inventory.domain.TransitGateStatus
import java.util.UUID

@Component
class TransitGateEnforcer(
    private val transitGateRepository: TransitGateRepository,
    private val tripStopRepository: TripStopRepository,
) {
    fun assertCanReserve(tripId: UUID, seatNumber: Int, fromStopOrder: Int) {
        val stops = tripStopRepository.listByTripId(tripId)
        if (stops.isEmpty()) return

        val firstOrder = stops.minOf { it.stopOrder }
        val lastOrder = stops.maxOf { it.stopOrder }

        if (fromStopOrder == firstOrder || fromStopOrder == lastOrder) {
            return
        }

        val sellingStop = stops.firstOrNull { it.stopOrder == fromStopOrder }
            ?: throw DomainRuleViolation("Stop order $fromStopOrder not found on trip")

        val stationId = sellingStop.stationId
            ?: throw DomainRuleViolation("Transit stop has no station")

        currentPrincipal()?.let { principal ->
            if (!principal.isSuperuser && principal.stationId != null && principal.stationId != stationId) {
                throw DomainRuleViolation("Reservation not allowed for station scope")
            }
        }

        val gates = transitGateRepository.listByTripId(tripId)
        gates
            .filter { it.stopOrder < fromStopOrder && it.status == TransitGateStatus.CLOSED }
            .forEach {
                throw DomainRuleViolation("TRANSIT_CLOSED: boarding completed at stop ${it.stopOrder}")
            }

        val gate = gates.firstOrNull { it.stationId == stationId && it.stopOrder == fromStopOrder }
            ?: throw DomainRuleViolation("TRANSIT_CLOSED: transit gate not configured")

        if (gate.status != TransitGateStatus.OPEN) {
            throw DomainRuleViolation("TRANSIT_CLOSED")
        }

        val allowed = gate.availableSeats ?: emptyList()
        if (seatNumber !in allowed) {
            throw DomainRuleViolation("TRANSIT_CLOSED: seat $seatNumber is not in transit quota")
        }
    }

    fun resolveFromStopOrder(tripId: UUID, requestedFromStopOrder: Int?): Int {
        if (requestedFromStopOrder != null) return requestedFromStopOrder

        val stationId = StationScope.currentStationId() ?: return 1
        val stop = tripStopRepository.listByTripId(tripId)
            .firstOrNull { it.stationId == stationId }
        return stop?.stopOrder ?: 1
    }
}
