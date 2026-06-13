package ru.transora.app.inventory

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.scheduling.TripRepository
import ru.transora.app.scheduling.TripStopRepository
import ru.transora.inventory.domain.SeatStatus
import ru.transora.inventory.domain.TransitGateStatus
import ru.transora.scheduling.domain.StopStatus
import java.time.Clock
import java.util.UUID

@Service
class TransitGateService(
    private val transitGateRepository: TransitGateRepository,
    private val tripRepository: TripRepository,
    private val tripStopRepository: TripStopRepository,
    private val seatRepository: SeatRepository,
    private val inventoryEventPublisher: InventoryEventPublisher,
) {
    fun listForTrip(tripId: UUID): List<TransitGateRecord> {
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")
        val principal = currentPrincipal()
        return if (principal?.isSuperuser == true) {
            transitGateRepository.listByTripId(tripId)
        } else {
            val stationId = StationScope.requireStationId()
            transitGateRepository.listByTripAndStation(tripId, stationId)
        }
    }

    @Transactional
    fun openGate(gateId: UUID, availableSeats: List<Int>, notes: String?): TransitGateRecord {
        val stationId = StationScope.requireStationId()
        val userId = currentPrincipal()?.userId
            ?: throw DomainRuleViolation("Authentication required")

        val gate = transitGateRepository.findByIdForUpdate(gateId)
            ?: throw NoSuchElementException("Transit gate $gateId was not found")

        if (gate.stationId != stationId) {
            throw DomainRuleViolation("Transit gate does not belong to current station")
        }
        if (gate.status == TransitGateStatus.CLOSED) {
            if (currentPrincipal()?.isSuperuser != true) {
                throw DomainRuleViolation("Closed transit gate can only be re-opened by system admin")
            }
        } else if (gate.status != TransitGateStatus.AWAITING_ARRIVAL) {
            throw DomainRuleViolation("Transit gate is already ${gate.status.name}")
        }

        if (availableSeats.isEmpty()) {
            throw DomainRuleViolation("availableSeats must not be empty")
        }

        val stop = tripStopRepository.listByTripId(gate.tripId)
            .firstOrNull { it.stopOrder == gate.stopOrder }
            ?: throw DomainRuleViolation("Stop ${gate.stopOrder} not found for trip")

        if (stop.stopStatus == StopStatus.UPCOMING) {
            throw DomainRuleViolation("Stop must be arrived before opening transit sales")
        }

        availableSeats.forEach { seatNumber ->
            val status = seatRepository.findStatusForUpdate(gate.tripId, seatNumber)
                ?: throw DomainRuleViolation("Seat $seatNumber was not found")
            if (status != SeatStatus.AVAILABLE) {
                throw DomainRuleViolation("Seat $seatNumber is not available for transit sale")
            }
        }

        val now = Clock.systemUTC().instant()
        transitGateRepository.markOpen(
            id = gateId,
            availableSeats = availableSeats.toIntArray(),
            openedBy = userId,
            notes = notes,
            openedAt = now,
        )

        inventoryEventPublisher.publishTransitGateOpened(
            gateId = gateId,
            tripId = gate.tripId,
            stationId = gate.stationId,
            stopOrder = gate.stopOrder,
            availableSeats = availableSeats,
        )

        return transitGateRepository.findById(gateId)!!
    }

    @Transactional
    fun closeGate(gateId: UUID, notes: String?): TransitGateRecord {
        val stationId = StationScope.requireStationId()
        val userId = currentPrincipal()?.userId
            ?: throw DomainRuleViolation("Authentication required")

        val gate = transitGateRepository.findByIdForUpdate(gateId)
            ?: throw NoSuchElementException("Transit gate $gateId was not found")

        if (gate.stationId != stationId) {
            throw DomainRuleViolation("Transit gate does not belong to current station")
        }
        if (gate.status != TransitGateStatus.OPEN) {
            throw DomainRuleViolation("Only OPEN transit gate can be closed")
        }

        val now = Clock.systemUTC().instant()
        transitGateRepository.markClosed(
            id = gateId,
            closedBy = userId,
            notes = notes,
            closedAt = now,
        )

        inventoryEventPublisher.publishTransitGateClosed(
            gateId = gateId,
            tripId = gate.tripId,
            stationId = gate.stationId,
            stopOrder = gate.stopOrder,
        )

        return transitGateRepository.findById(gateId)!!
    }
}
