package ru.transora.app.inventory

import org.springframework.stereotype.Component
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import java.util.UUID

@Component
class SalesRestrictionEnforcer(
    private val salesRestrictionRepository: SalesRestrictionRepository,
) {
    fun assertSeatAllowed(tripId: UUID, seatNumber: Int, stationId: UUID?) {
        val resolvedStationId = stationId ?: StationScope.currentStationId() ?: return
        val restriction = salesRestrictionRepository.findActiveForTripAndStation(tripId, resolvedStationId)
            ?: return
        if (seatNumber !in restriction.allowedSeats) {
            throw DomainRuleViolation("NOT_IN_QUOTA")
        }
    }

    fun isSeatAllowed(tripId: UUID, seatNumber: Int, stationId: UUID?): Boolean {
        val resolvedStationId = stationId ?: StationScope.currentStationId() ?: return true
        val restriction = salesRestrictionRepository.findActiveForTripAndStation(tripId, resolvedStationId)
            ?: return true
        return seatNumber in restriction.allowedSeats
    }
}
