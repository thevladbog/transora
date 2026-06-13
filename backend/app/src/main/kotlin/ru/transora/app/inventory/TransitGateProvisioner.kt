package ru.transora.app.inventory

import org.springframework.stereotype.Service
import ru.transora.app.scheduling.TripStopRepository
import java.util.UUID

@Service
class TransitGateProvisioner(
    private val tripStopRepository: TripStopRepository,
    private val transitGateRepository: TransitGateRepository,
) {
    fun provisionForTrip(tripId: UUID) {
        val stops = tripStopRepository.listByTripId(tripId)
        if (stops.isEmpty()) return

        val firstOrder = stops.minOf { it.stopOrder }
        val lastOrder = stops.maxOf { it.stopOrder }

        stops
            .filter { it.stationId != null }
            .filter { it.stopOrder != firstOrder && it.stopOrder != lastOrder }
            .forEach { stop ->
                transitGateRepository.insertAwaitingGate(
                    tripId = tripId,
                    stationId = stop.stationId!!,
                    stopOrder = stop.stopOrder,
                )
            }
    }
}
