package ru.transora.app.scheduling

import org.springframework.stereotype.Component
import ru.transora.scheduling.domain.RouteStop
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.TripStop
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@Component
class TripStopPlanner(
    private val tripStopRepository: TripStopRepository,
) {
    fun createTripStops(
        tripId: UUID,
        routeStops: List<RouteStop>,
        tripDate: LocalDate,
        departureTime: LocalTime,
        zoneId: ZoneId,
    ): List<TripStop> {
        var currentTime = ZonedDateTime.of(tripDate, departureTime, zoneId)
        val now = Clock.systemUTC().instant()
        val created = mutableListOf<TripStop>()

        routeStops.forEach { routeStop ->
            val scheduledArrival: Instant?
            val scheduledDeparture: Instant

            if (routeStop.stopOrder == 1) {
                scheduledArrival = null
                scheduledDeparture = currentTime.toInstant()
            } else {
                scheduledArrival = currentTime.toInstant()
                scheduledDeparture = currentTime.plusMinutes(routeStop.dwellTimeMin.toLong()).toInstant()
            }

            val stop = TripStop(
                id = UUID.randomUUID(),
                tripId = tripId,
                routeStopId = routeStop.id,
                stopOrder = routeStop.stopOrder,
                stopName = routeStop.stopName,
                stationId = routeStop.stationId,
                isExternal = routeStop.isExternal,
                scheduledArrival = scheduledArrival,
                scheduledDeparture = scheduledDeparture,
                estimatedArrival = scheduledArrival,
                estimatedDeparture = scheduledDeparture,
                actualArrival = null,
                actualDeparture = null,
                stopStatus = StopStatus.UPCOMING,
                updatedAt = now,
            )
            tripStopRepository.insert(stop)
            created += stop

            if (routeStop.stopOrder < routeStops.size) {
                val nextStop = routeStops[routeStop.stopOrder]
                val travelMinutes = nextStop.scheduledDurationMin ?: 0
                currentTime = ZonedDateTime.ofInstant(scheduledDeparture, zoneId)
                    .plusMinutes(travelMinutes.toLong())
            }
        }

        return created
    }
}
