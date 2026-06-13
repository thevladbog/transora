package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.sales.TicketRepository
import ru.transora.scheduling.domain.Trip
import ru.transora.scheduling.domain.TripStatus
import ru.transora.scheduling.domain.TripStop
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@Service
class TripService(
    private val tripRepository: TripRepository,
    private val tripStopRepository: TripStopRepository,
    private val ticketRepository: TicketRepository,
    private val routeRepository: RouteRepository,
    private val serviceStationRepository: ServiceStationRepository,
    private val vehicleRepository: VehicleRepository,
    private val driverRepository: DriverRepository,
    private val tripStopPlanner: TripStopPlanner,
    private val tripConflictChecker: TripConflictChecker,
    private val tripAuditLogRepository: TripAuditLogRepository,
    private val schedulingEventPublisher: SchedulingEventPublisher,
) {
    @Transactional
    fun createTrip(request: CreateTripRequest): Trip {
        val now = Clock.systemUTC().instant()
        val stationCode = request.departureStationCode?.trim()?.takeIf { it.isNotEmpty() } ?: "T1"
        val trip = Trip(
            id = UUID.randomUUID(),
            routeNumber = request.routeNumber.trim(),
            departureStation = request.departureStation.trim(),
            arrivalStation = request.arrivalStation.trim(),
            departureStationCode = stationCode,
            departureTime = request.departureTime,
            expectedDepartureTime = request.departureTime,
            delayMinutes = null,
            platform = request.platform?.trim()?.takeIf { it.isNotEmpty() },
            status = TripStatus.OPEN,
            createdAt = now,
            tripNumber = request.routeNumber.trim(),
        )

        tripRepository.insert(trip)
        publishTripCreated(trip, request.seatCount, stationCode)

        return trip
    }

    @Transactional
    fun createTripFromRoute(request: CreateTripFromRouteRequest): Trip {
        val routeWithStops = routeRepository.findWithStops(request.routeId)
            ?: throw NoSuchElementException("Route ${request.routeId} was not found")
        val stops = routeWithStops.stops
        if (stops.size < 2) {
            throw DomainRuleViolation("Route must have at least 2 stops (BR-SCH-001)")
        }
        if (request.tripDate.isBefore(LocalDate.now(Clock.systemUTC()).minusDays(1))) {
            throw DomainRuleViolation("Trip cannot be created for a past date (BR-SCH-021)")
        }

        val tripNumber = request.tripNumber.trim()
        if (tripRepository.existsByRouteDateTripNumber(request.routeId, request.tripDate, tripNumber)) {
            throw DomainRuleViolation("Trip $tripNumber already exists on ${request.tripDate} for this route")
        }

        val firstStop = stops.first()
        val lastStop = stops.last()
        val departureStationCode = firstStop.stationId?.let { stationId ->
            serviceStationRepository.findById(stationId)?.code
        } ?: "T1"

        val zoneId = firstStop.stationId?.let { stationId ->
            serviceStationRepository.findById(stationId)?.timezone
        }?.let { ZoneId.of(it) } ?: ZoneId.of("Europe/Moscow")

        val departureInstant = ZonedDateTime.of(request.tripDate, request.departureTime, zoneId).toInstant()
        val now = Clock.systemUTC().instant()
        val tripId = UUID.randomUUID()

        request.vehicleId?.let { vehicleRepository.findById(it) ?: throw NoSuchElementException("Vehicle $it not found") }
        request.driverId?.let { driverRepository.findById(it) ?: throw NoSuchElementException("Driver $it not found") }

        val initialStatus = when {
            request.openSales && request.vehicleId != null -> TripStatus.OPEN
            else -> TripStatus.PLANNED
        }
        if (request.openSales && request.vehicleId == null) {
            throw DomainRuleViolation("Vehicle is required to open sales (BR-SCH-023)")
        }

        val trip = Trip(
            id = tripId,
            routeNumber = tripNumber,
            departureStation = firstStop.stopName,
            arrivalStation = lastStop.stopName,
            departureStationCode = departureStationCode,
            departureTime = departureInstant,
            expectedDepartureTime = departureInstant,
            delayMinutes = null,
            platform = request.platform,
            status = initialStatus,
            createdAt = now,
            routeId = request.routeId,
            vehicleId = request.vehicleId,
            driverId = request.driverId,
            tripDate = request.tripDate,
            tripNumber = tripNumber,
            autoGenerated = false,
        )
        tripRepository.insert(trip)

        val plannedStops = tripStopPlanner.createTripStops(
            tripId = tripId,
            routeStops = stops,
            tripDate = request.tripDate,
            departureTime = request.departureTime,
            zoneId = zoneId,
        )

        request.vehicleId?.let { tripConflictChecker.assertNoVehicleConflict(it, plannedStops, tripId) }
        request.driverId?.let { tripConflictChecker.assertNoDriverConflict(it, plannedStops, tripId) }

        if (initialStatus == TripStatus.OPEN && request.vehicleId != null) {
            val seatCount = vehicleRepository.findById(request.vehicleId)!!.totalSeats
            publishTripCreated(trip, seatCount, departureStationCode)
        }

        return trip
    }

    fun listTrips(filter: TripListFilter = TripListFilter()): List<Trip> {
        val now = Clock.systemUTC().instant()
        val from = filter.from ?: now.minus(Duration.ofHours(1))
        val to = filter.to ?: now.plus(Duration.ofHours(filter.horizonHours.toLong()))
        return tripRepository.listFiltered(
            TripFilter(
                stationCode = filter.stationCode,
                from = from,
                to = to,
                limit = filter.limit,
            ),
        )
    }

    fun getTrip(id: UUID, includeStops: Boolean = false): TripDetail {
        val trip = tripRepository.findById(id) ?: throw NoSuchElementException("Trip $id was not found")
        val stops = if (includeStops) tripStopRepository.listByTripId(id) else emptyList()
        return TripDetail(trip, stops)
    }

    @Transactional
    fun updateTrip(id: UUID, request: UpdateTripRequest): Trip {
        val existing = tripRepository.findByIdForUpdate(id)
            ?: throw NoSuchElementException("Trip $id was not found")

        val principal = currentPrincipal()
        var vehicleId = existing.vehicleId
        var driverId = existing.driverId

        if (request.vehicleId != null || request.driverId != null) {
            if (existing.status == TripStatus.DEPARTED ||
                existing.status == TripStatus.IN_TRANSIT ||
                existing.status == TripStatus.ARRIVED ||
                existing.status == TripStatus.COMPLETED
            ) {
                throw DomainRuleViolation("Cannot change vehicle/driver after departure (BR-SCH-024)")
            }
            val stops = tripStopRepository.listByTripId(id)
            request.vehicleId?.let { newVehicleId ->
                vehicleRepository.findById(newVehicleId)
                    ?: throw NoSuchElementException("Vehicle $newVehicleId not found")
                tripConflictChecker.assertNoVehicleConflict(newVehicleId, stops, id)
                if (newVehicleId != existing.vehicleId) {
                    val oldVehicleId = existing.vehicleId?.toString()
                    tripRepository.updateResources(id, newVehicleId, driverId)
                    vehicleId = newVehicleId
                    tripAuditLogRepository.insert(
                        tripId = id,
                        eventType = "VEHICLE_REPLACED",
                        oldValue = mapOf("vehicleId" to oldVehicleId),
                        newValue = mapOf("vehicleId" to newVehicleId.toString()),
                        changedBy = principal?.userId,
                        stationId = principal?.stationId,
                    )
                    val seatCount = vehicleRepository.findById(newVehicleId)!!.totalSeats
                    schedulingEventPublisher.publish(
                        eventType = "scheduling.trip.vehicle_changed",
                        tripId = id,
                        payload = mapOf(
                            "tripId" to id.toString(),
                            "previousVehicleId" to oldVehicleId,
                            "vehicleId" to newVehicleId.toString(),
                            "seatCount" to seatCount,
                        ),
                        sourceStationId = principal?.stationId,
                    )
                }
            }
            request.driverId?.let { newDriverId ->
                driverRepository.findById(newDriverId)
                    ?: throw NoSuchElementException("Driver $newDriverId not found")
                tripConflictChecker.assertNoDriverConflict(newDriverId, stops, id)
                driverId = newDriverId
                tripRepository.updateResources(id, vehicleId, driverId)
            }
        }

        val newStatus = request.status ?: existing.status
        if (newStatus == TripStatus.OPEN && vehicleId == null) {
            throw DomainRuleViolation("Vehicle is required before opening sales (BR-SCH-023)")
        }
        validateStatusTransition(existing.id, existing.status, newStatus)

        val expectedDepartureTime = request.expectedDepartureTime ?: existing.expectedDepartureTime
        val delayMinutes = request.delayMinutes ?: computeDelayMinutes(existing.departureTime, expectedDepartureTime)
        val platform = request.platform?.trim()?.takeIf { it.isNotEmpty() } ?: existing.platform

        val delayChanged = existing.delayMinutes != delayMinutes ||
            existing.expectedDepartureTime != expectedDepartureTime

        tripRepository.update(
            id = id,
            expectedDepartureTime = expectedDepartureTime,
            delayMinutes = delayMinutes,
            platform = platform,
            status = newStatus,
        )

        val updated = tripRepository.findById(id) ?: throw IllegalStateException("Trip $id disappeared after update")

        if (delayChanged) {
            val updatedStops = recalculateEstimatedStops(id, delayMinutes ?: 0)
            tripAuditLogRepository.insert(
                tripId = id,
                eventType = "DELAY_UPDATED",
                oldValue = mapOf(
                    "delayMinutes" to existing.delayMinutes,
                    "expectedDepartureTime" to existing.expectedDepartureTime.toString(),
                ),
                newValue = mapOf(
                    "delayMinutes" to delayMinutes,
                    "expectedDepartureTime" to expectedDepartureTime.toString(),
                    "reason" to request.delayReason,
                ),
                changedBy = principal?.userId,
                stationId = principal?.stationId,
            )
            schedulingEventPublisher.publish(
                eventType = "scheduling.trip.delay_updated",
                tripId = id,
                payload = mapOf(
                    "tripId" to id.toString(),
                    "tripNumber" to updated.tripNumber,
                    "tripDate" to updated.tripDate?.toString(),
                    "routeId" to updated.routeId?.toString(),
                    "delayMinutes" to delayMinutes,
                    "updatedStops" to updatedStops.map { stop ->
                        mapOf(
                            "stopOrder" to stop.stopOrder,
                            "stopName" to stop.stopName,
                            "estimatedArrival" to stop.estimatedArrival?.toString(),
                            "estimatedDeparture" to stop.estimatedDeparture?.toString(),
                        )
                    },
                ),
                sourceStationId = principal?.stationId,
            )
        }

        if (existing.status != newStatus) {
            if (newStatus == TripStatus.CANCELLED) {
                schedulingEventPublisher.publish(
                    eventType = "scheduling.trip.cancelled",
                    tripId = id,
                    payload = mapOf(
                        "tripId" to id.toString(),
                        "previousStatus" to existing.status.name,
                        "departureStationCode" to updated.departureStationCode,
                    ),
                    sourceStationId = principal?.stationId,
                )
            } else {
                schedulingEventPublisher.publish(
                    eventType = "scheduling.trip.status_changed",
                    tripId = id,
                    payload = mapOf(
                        "tripId" to id.toString(),
                        "status" to newStatus.name,
                        "previousStatus" to existing.status.name,
                        "departureStationCode" to updated.departureStationCode,
                    ),
                    sourceStationId = principal?.stationId,
                )
            }
            tripAuditLogRepository.insert(
                tripId = id,
                eventType = "STATUS_CHANGED",
                oldValue = mapOf("status" to existing.status.name),
                newValue = mapOf("status" to newStatus.name),
                changedBy = principal?.userId,
                stationId = principal?.stationId,
            )
        }

        if (existing.status != TripStatus.OPEN && newStatus == TripStatus.OPEN && vehicleId != null) {
            val seatCount = vehicleRepository.findById(vehicleId)?.totalSeats
                ?: throw IllegalStateException("Vehicle $vehicleId has no seat count")
            publishTripCreated(updated, seatCount, updated.departureStationCode)
        }

        if (existing.status != TripStatus.BOARDING && newStatus == TripStatus.BOARDING) {
            schedulingEventPublisher.publish(
                eventType = "boarding.started",
                tripId = id,
                payload = mapOf(
                    "tripId" to id.toString(),
                    "departureStationCode" to updated.departureStationCode,
                    "routeNumber" to updated.routeNumber,
                    "platform" to updated.platform,
                    "expectedDepartureTime" to updated.expectedDepartureTime.toString(),
                ),
            )
        }

        if (existing.status != TripStatus.COMPLETED && newStatus == TripStatus.COMPLETED) {
            schedulingEventPublisher.publish(
                eventType = "trip.completed",
                tripId = id,
                payload = mapOf(
                    "tripId" to id.toString(),
                    "departureStationCode" to updated.departureStationCode,
                    "routeNumber" to updated.routeNumber,
                    "status" to updated.status.name,
                ),
            )
        }

        return updated
    }

    @Transactional
    fun cancelTripIfAllowed(tripId: UUID, reason: String? = null) {
        val trip = tripRepository.findByIdForUpdate(tripId) ?: return
        if (trip.status == TripStatus.CANCELLED) return
        if (ticketRepository.countIssuedByTripId(tripId) > 0) {
            throw DomainRuleViolation("Trip cannot be cancelled while issued tickets exist (BR-SCH-025)")
        }
        tripRepository.updateStatus(tripId, TripStatus.CANCELLED)
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.cancelled",
            tripId = tripId,
            payload = mapOf(
                "tripId" to tripId.toString(),
                "previousStatus" to trip.status.name,
                "reason" to reason,
            ),
        )
    }

    private fun recalculateEstimatedStops(tripId: UUID, delayMinutes: Int): List<TripStop> {
        val stops = tripStopRepository.listByTripId(tripId)
        if (stops.isEmpty()) return stops

        val shift = Duration.ofMinutes(delayMinutes.toLong())
        stops.forEach { stop ->
            val newEstimatedArrival = stop.scheduledArrival?.plus(shift)
            val newEstimatedDeparture = stop.scheduledDeparture.plus(shift)
            tripStopRepository.updateEstimatedTimes(
                stopId = stop.id,
                estimatedArrival = newEstimatedArrival,
                estimatedDeparture = newEstimatedDeparture,
            )
        }
        return tripStopRepository.listByTripId(tripId)
    }

    private fun publishTripCreated(trip: Trip, seatCount: Int, stationCode: String) {
        schedulingEventPublisher.publish(
            eventType = "scheduling.trip.created",
            tripId = trip.id,
            payload = mapOf(
                "tripId" to trip.id.toString(),
                "routeId" to trip.routeId?.toString(),
                "routeNumber" to trip.routeNumber,
                "tripNumber" to (trip.tripNumber ?: trip.routeNumber),
                "departureStationCode" to stationCode,
                "departureStation" to trip.departureStation,
                "arrivalStation" to trip.arrivalStation,
                "departureTime" to trip.departureTime.toString(),
                "expectedDepartureTime" to trip.expectedDepartureTime.toString(),
                "platform" to trip.platform,
                "seatCount" to seatCount,
                "autoGenerated" to trip.autoGenerated,
                "tripDate" to trip.tripDate?.toString(),
            ),
        )
    }

    private fun validateStatusTransition(tripId: UUID, current: TripStatus, next: TripStatus) {
        if (current == next) return
        if (current == TripStatus.CANCELLED || current == TripStatus.COMPLETED) {
            throw DomainRuleViolation("Trip in status $current cannot transition to $next")
        }
        if (current == TripStatus.ARRIVED && next != TripStatus.COMPLETED) {
            throw DomainRuleViolation("Trip in status $current cannot transition to $next")
        }
        if (next == TripStatus.CANCELLED && ticketRepository.countIssuedByTripId(tripId) > 0) {
            throw DomainRuleViolation(
                "Trip cannot be cancelled while issued tickets exist (BR-SCH-025)",
            )
        }
        val allowed = when (current) {
            TripStatus.PLANNED -> setOf(TripStatus.OPEN, TripStatus.CANCELLED)
            TripStatus.OPEN -> setOf(TripStatus.BOARDING, TripStatus.DEPARTED, TripStatus.CANCELLED)
            TripStatus.BOARDING -> setOf(TripStatus.DEPARTED, TripStatus.CANCELLED)
            TripStatus.DEPARTED -> setOf(TripStatus.IN_TRANSIT, TripStatus.ARRIVED)
            TripStatus.IN_TRANSIT -> setOf(TripStatus.ARRIVED)
            TripStatus.ARRIVED -> setOf(TripStatus.COMPLETED)
            else -> emptySet()
        }
        if (next !in allowed) {
            throw DomainRuleViolation("Trip cannot transition from $current to $next")
        }
    }

    private fun computeDelayMinutes(departureTime: Instant, expectedDepartureTime: Instant): Int? {
        val delay = Duration.between(departureTime, expectedDepartureTime).toMinutes().toInt()
        return delay.takeIf { it > 0 }
    }
}

data class TripDetail(
    val trip: Trip,
    val stops: List<TripStop>,
)

data class TripListFilter(
    val stationCode: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val horizonHours: Int = 3,
    val limit: Int = 100,
)

data class CreateTripFromRouteRequest(
    val routeId: UUID,
    val tripDate: LocalDate,
    val tripNumber: String,
    val departureTime: LocalTime,
    val vehicleId: UUID? = null,
    val driverId: UUID? = null,
    val platform: String? = null,
    val openSales: Boolean = false,
)
