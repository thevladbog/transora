package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.Trip
import ru.transora.scheduling.domain.TripStatus
import ru.transora.scheduling.domain.TripStop
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class TripStopService(
    private val tripRepository: TripRepository,
    private val tripStopRepository: TripStopRepository,
    private val tripAuditLogRepository: TripAuditLogRepository,
    private val schedulingEventPublisher: SchedulingEventPublisher,
) {
    fun listStops(tripId: UUID): List<TripStop> {
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")
        return tripStopRepository.listByTripId(tripId)
    }

    @Transactional
    fun updateStop(
        tripId: UUID,
        stopId: UUID,
        request: UpdateTripStopRequest,
    ): TripStop {
        val trip = tripRepository.findByIdForUpdate(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val stop = tripStopRepository.findByIdForUpdate(tripId, stopId)
            ?: throw NoSuchElementException("Stop $stopId was not found on trip $tripId")

        val principal = currentPrincipal()
        val newStatus = request.stopStatus ?: stop.stopStatus
        validateStopStatusTransition(stop, newStatus)

        if (request.stopStatus == StopStatus.SKIPPED && stop.actualArrival != null) {
            throw DomainRuleViolation("Cannot skip stop after actual arrival was recorded")
        }

        val previousStop = tripStopRepository.listByTripId(tripId)
            .filter { it.stopOrder < stop.stopOrder }
            .maxByOrNull { it.stopOrder }
        if (previousStop != null && previousStop.stopStatus != StopStatus.DEPARTED && newStatus != StopStatus.UPCOMING) {
            if (newStatus != StopStatus.SKIPPED) {
                throw DomainRuleViolation("Previous stop ${previousStop.stopOrder} must be DEPARTED first")
            }
        }

        tripStopRepository.update(
            stopId = stopId,
            estimatedArrival = request.estimatedArrival ?: stop.estimatedArrival,
            estimatedDeparture = request.estimatedDeparture ?: stop.estimatedDeparture,
            actualArrival = request.actualArrival ?: stop.actualArrival,
            actualDeparture = request.actualDeparture ?: stop.actualDeparture,
            stopStatus = newStatus,
            updatedBy = principal?.userId,
        )

        tripAuditLogRepository.insert(
            tripId = tripId,
            eventType = "STOP_UPDATED",
            oldValue = mapOf("stopOrder" to stop.stopOrder, "stopStatus" to stop.stopStatus.name),
            newValue = mapOf(
                "stopOrder" to stop.stopOrder,
                "stopStatus" to newStatus.name,
                "reason" to request.reason,
            ),
            changedBy = principal?.userId,
            stationId = principal?.stationId,
        )

        return tripStopRepository.listByTripId(tripId).first { it.id == stopId }
    }

    @Transactional
    fun recordArrival(tripId: UUID, stopId: UUID, at: Instant? = null): TripStop {
        val arrivalTime = at ?: Clock.systemUTC().instant()
        val stop = tripStopRepository.findByIdForUpdate(tripId, stopId)
            ?: throw NoSuchElementException("Stop $stopId was not found on trip $tripId")
        val allStops = tripStopRepository.listByTripId(tripId)
        val lastStop = allStops.maxBy { it.stopOrder }

        val updated = updateStop(
            tripId,
            stopId,
            UpdateTripStopRequest(
                actualArrival = arrivalTime,
                stopStatus = StopStatus.ARRIVED,
            ),
        )

        if (stop.id == lastStop.id) {
            syncTripStatus(tripId, TripStatus.ARRIVED)
            publishArrived(tripId, stop)
        } else {
            publishStopArrived(tripId, stop)
        }

        return updated
    }

    @Transactional
    fun recordDeparture(tripId: UUID, stopId: UUID, at: Instant? = null): TripStop {
        val departureTime = at ?: Clock.systemUTC().instant()
        val stop = tripStopRepository.findByIdForUpdate(tripId, stopId)
            ?: throw NoSuchElementException("Stop $stopId was not found on trip $tripId")
        val allStops = tripStopRepository.listByTripId(tripId)
        val firstStop = allStops.minBy { it.stopOrder }
        val lastStop = allStops.maxBy { it.stopOrder }

        if (stop.stopOrder > 1) {
            val previous = allStops.first { it.stopOrder == stop.stopOrder - 1 }
            if (previous.stopStatus != StopStatus.DEPARTED && previous.stopStatus != StopStatus.SKIPPED) {
                throw DomainRuleViolation("Previous stop must be departed before departing stop ${stop.stopOrder}")
            }
        }

        val updated = updateStop(
            tripId,
            stopId,
            UpdateTripStopRequest(
                actualDeparture = departureTime,
                stopStatus = StopStatus.DEPARTED,
            ),
        )

        when {
            stop.id == firstStop.id -> {
                syncTripStatus(tripId, TripStatus.DEPARTED)
                publishDeparted(tripId, stop)
            }
            stop.id != lastStop.id -> {
                syncTripStatus(tripId, TripStatus.IN_TRANSIT)
            }
        }

        return updated
    }

    private fun validateStopStatusTransition(stop: TripStop, next: StopStatus) {
        if (stop.stopStatus == next) return
        val allowed = when (stop.stopStatus) {
            StopStatus.UPCOMING -> setOf(StopStatus.ARRIVED, StopStatus.SKIPPED, StopStatus.DEPARTED)
            StopStatus.ARRIVED -> setOf(StopStatus.DEPARTED, StopStatus.SKIPPED)
            StopStatus.DEPARTED, StopStatus.SKIPPED -> emptySet()
        }
        if (next !in allowed) {
            throw DomainRuleViolation("Stop cannot transition from ${stop.stopStatus} to $next")
        }
    }

    private fun syncTripStatus(tripId: UUID, status: TripStatus) {
        val trip = tripRepository.findByIdForUpdate(tripId) ?: return
        if (trip.status == status) return
        tripRepository.update(
            id = tripId,
            expectedDepartureTime = trip.expectedDepartureTime,
            delayMinutes = trip.delayMinutes,
            platform = trip.platform,
            status = status,
        )
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.status_changed",
            tripId = tripId,
            payload = mapOf(
                "tripId" to tripId.toString(),
                "status" to status.name,
                "previousStatus" to trip.status.name,
                "departureStationCode" to trip.departureStationCode,
            ),
        )
    }

    private fun publishDeparted(tripId: UUID, stop: TripStop) {
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.departed",
            tripId = tripId,
            payload = mapOf(
                "tripId" to tripId.toString(),
                "stopOrder" to stop.stopOrder,
                "stopName" to stop.stopName,
                "actualDeparture" to stop.actualDeparture?.toString(),
            ),
            sourceStationId = stop.stationId,
        )
    }

    private fun publishArrived(tripId: UUID, stop: TripStop) {
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.arrived",
            tripId = tripId,
            payload = mapOf(
                "tripId" to tripId.toString(),
                "stopOrder" to stop.stopOrder,
                "stopName" to stop.stopName,
                "actualArrival" to stop.actualArrival?.toString(),
            ),
            sourceStationId = stop.stationId,
        )
    }

    private fun publishStopArrived(tripId: UUID, stop: TripStop) {
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.stop_arrived",
            tripId = tripId,
            payload = mapOf(
                "tripId" to tripId.toString(),
                "stopOrder" to stop.stopOrder,
                "stationId" to stop.stationId?.toString(),
                "stopName" to stop.stopName,
                "actualArrival" to stop.actualArrival?.toString(),
            ),
            sourceStationId = stop.stationId,
        )
    }
}

data class UpdateTripStopRequest(
    val estimatedArrival: Instant? = null,
    val estimatedDeparture: Instant? = null,
    val actualArrival: Instant? = null,
    val actualDeparture: Instant? = null,
    val stopStatus: StopStatus? = null,
    val reason: String? = null,
)
