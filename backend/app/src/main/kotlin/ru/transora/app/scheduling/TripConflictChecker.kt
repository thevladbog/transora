package ru.transora.app.scheduling

import org.springframework.stereotype.Component
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.scheduling.domain.TripStop
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class TripConflictChecker(
    private val tripRepository: TripRepository,
) {
    private val buffer = Duration.ofMinutes(30)

    fun assertNoVehicleConflict(
        vehicleId: UUID,
        stops: List<TripStop>,
        excludeTripId: UUID? = null,
    ) {
        val interval = tripInterval(stops) ?: return
        val conflicts = tripRepository.findConflictingTrips(
            resourceColumn = "vehicle_id",
            resourceId = vehicleId,
            intervalStart = interval.first.minus(buffer),
            intervalEnd = interval.second.plus(buffer),
            excludeTripId = excludeTripId,
        )
        if (conflicts.isNotEmpty()) {
            throw DomainRuleViolation(
                "Vehicle is already assigned to overlapping trip ${conflicts.first().id} (BR-SCH-031)",
            )
        }
    }

    fun assertNoDriverConflict(
        driverId: UUID,
        stops: List<TripStop>,
        excludeTripId: UUID? = null,
    ) {
        val interval = tripInterval(stops) ?: return
        val conflicts = tripRepository.findConflictingTrips(
            resourceColumn = "driver_id",
            resourceId = driverId,
            intervalStart = interval.first.minus(buffer),
            intervalEnd = interval.second.plus(buffer),
            excludeTripId = excludeTripId,
        )
        if (conflicts.isNotEmpty()) {
            throw DomainRuleViolation(
                "Driver is already assigned to overlapping trip ${conflicts.first().id} (BR-SCH-030)",
            )
        }
    }

    private fun tripInterval(stops: List<TripStop>): Pair<Instant, Instant>? {
        if (stops.isEmpty()) return null
        val first = stops.minBy { it.stopOrder }
        val last = stops.maxBy { it.stopOrder }
        val start = first.scheduledDeparture
        val end = last.scheduledArrival ?: last.scheduledDeparture
        return start to end
    }
}
