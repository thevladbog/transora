package ru.transora.app.inventory

import org.springframework.stereotype.Service
import ru.transora.app.iam.security.StationScope
import ru.transora.app.scheduling.TripRepository
import ru.transora.app.scheduling.TripStopRepository
import ru.transora.inventory.domain.TransitGateStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StationSeatView(
    val tripId: UUID,
    val stationId: UUID?,
    val requestedAt: Instant,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val seats: List<StationSeatEntry>,
    val transitGate: TransitGateSummary?,
)

data class StationSeatEntry(
    val seatNumber: Int,
    val status: String,
    val availableForStation: Boolean,
    val restrictionReason: String?,
)

data class TransitGateSummary(
    val status: String,
    val availableSeats: List<Int>?,
    val openedAt: Instant?,
)

@Service
class StationSeatAvailabilityService(
    private val tripRepository: TripRepository,
    private val tripStopRepository: TripStopRepository,
    private val seatRepository: SeatRepository,
    private val segmentOccupancyService: SegmentOccupancyService,
    private val salesRestrictionEnforcer: SalesRestrictionEnforcer,
    private val seatBlockEnforcer: SeatBlockEnforcer,
    private val transitGateEnforcer: TransitGateEnforcer,
    private val transitGateRepository: TransitGateRepository,
    private val reservationRepository: ReservationRepository,
) {
    fun buildStationView(tripId: UUID, toStopOrder: Int?): StationSeatView {
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")
        val stationId = StationScope.currentStationId()
        val fromStop = transitGateEnforcer.resolveFromStopOrder(tripId, null)
        val toStop = toStopOrder ?: segmentOccupancyService.resolveToStopOrder(tripId, fromStop, null)

        val stops = tripStopRepository.listByTripId(tripId)
        val firstOrder = stops.minOfOrNull { it.stopOrder } ?: 1
        val lastOrder = stops.maxOfOrNull { it.stopOrder } ?: toStop
        val isIntermediate = fromStop != firstOrder && fromStop != lastOrder

        val transitGate = if (isIntermediate && stationId != null) {
            transitGateRepository.findByTripAndStation(tripId, stationId)?.let {
                TransitGateSummary(
                    status = it.status.name,
                    availableSeats = it.availableSeats,
                    openedAt = it.openedAt,
                )
            }
        } else {
            null
        }

        val seats = seatRepository.listByTrip(tripId).map { seat ->
            val reason = when {
                seat.requiresReaccommodation -> "REACCOMMODATION_REQUIRED"
                else -> evaluateRestriction(
                    tripId = tripId,
                    seatNumber = seat.seatNumber,
                    fromStop = fromStop,
                    toStop = toStop,
                    stationId = stationId,
                    physicalStatus = seat.status.name,
                )
            }
            StationSeatEntry(
                seatNumber = seat.seatNumber,
                status = seat.status.name,
                availableForStation = reason == null,
                restrictionReason = reason,
            )
        }

        return StationSeatView(
            tripId = tripId,
            stationId = stationId,
            requestedAt = Clock.systemUTC().instant(),
            fromStopOrder = fromStop,
            toStopOrder = toStop,
            seats = seats,
            transitGate = transitGate,
        )
    }

    private fun evaluateRestriction(
        tripId: UUID,
        seatNumber: Int,
        fromStop: Int,
        toStop: Int,
        stationId: UUID?,
        physicalStatus: String,
    ): String? {
        if (seatBlockEnforcer.isBlocked(tripId, seatNumber)) {
            return "MANUAL_BLOCK"
        }
        if (!salesRestrictionEnforcer.isSeatAllowed(tripId, seatNumber, stationId)) {
            return "NOT_IN_QUOTA"
        }

        if (stationId != null) {
            val transitReason = evaluateTransit(tripId, seatNumber, fromStop, stationId)
            if (transitReason != null) return transitReason
        }

        val segmentMode = segmentOccupancyService.isSegmentMode(tripId)
        if (segmentMode) {
            if (!segmentOccupancyService.isSegmentFree(tripId, seatNumber, fromStop, toStop)) {
                if (hasActiveReservationOverlap(tripId, seatNumber, fromStop, toStop)) {
                    return "RESERVED"
                }
                return "SEGMENT_OCCUPIED"
            }
        } else if (physicalStatus != "AVAILABLE") {
            return when (physicalStatus) {
                "RESERVED" -> "RESERVED"
                "SOLD" -> "SEGMENT_OCCUPIED"
                else -> physicalStatus
            }
        }

        return null
    }

    private fun hasActiveReservationOverlap(
        tripId: UUID,
        seatNumber: Int,
        fromStop: Int,
        toStop: Int,
    ): Boolean {
        val now = Clock.systemUTC().instant()
        return reservationRepository.listActiveByTripSeat(tripId, seatNumber).any { reservation ->
            if (reservation.expiresAt.isBefore(now)) return@any false
            val rFrom = reservation.fromStopOrder ?: return@any false
            val rTo = reservation.toStopOrder ?: return@any false
            segmentOccupancyService.segmentsOverlap(fromStop, toStop, rFrom, rTo)
        }
    }

    private fun evaluateTransit(
        tripId: UUID,
        seatNumber: Int,
        fromStop: Int,
        stationId: UUID,
    ): String? {
        val stops = tripStopRepository.listByTripId(tripId)
        if (stops.isEmpty()) return null
        val firstOrder = stops.minOf { it.stopOrder }
        val lastOrder = stops.maxOf { it.stopOrder }
        if (fromStop == firstOrder || fromStop == lastOrder) return null

        val gates = transitGateRepository.listByTripId(tripId)
        gates
            .filter { it.stopOrder < fromStop && it.status == TransitGateStatus.CLOSED }
            .forEach { return "TRANSIT_CLOSED" }

        val gate = gates.firstOrNull { it.stationId == stationId && it.stopOrder == fromStop }
            ?: return "TRANSIT_CLOSED"

        if (gate.status != TransitGateStatus.OPEN) return "TRANSIT_CLOSED"
        val allowed = gate.availableSeats ?: emptyList()
        if (seatNumber !in allowed) return "TRANSIT_CLOSED"
        return null
    }
}
