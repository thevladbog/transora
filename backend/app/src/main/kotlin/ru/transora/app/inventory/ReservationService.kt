package ru.transora.app.inventory

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.scheduling.TripRepository
import ru.transora.inventory.domain.Reservation
import ru.transora.inventory.domain.ReservationStatus
import ru.transora.inventory.domain.SeatStatus
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class ReservationService(
    private val tripRepository: TripRepository,
    private val tripInventoryRepository: TripInventoryRepository,
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val seatSaleRepository: SeatSaleRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val transitGateEnforcer: TransitGateEnforcer,
    private val segmentOccupancyService: SegmentOccupancyService,
    private val salesRestrictionEnforcer: SalesRestrictionEnforcer,
    private val seatBlockEnforcer: SeatBlockEnforcer,
) {
    companion object {
        const val MAX_ACTIVE_RESERVATIONS_PER_SESSION = 10
        private val RESERVATION_TTL = Duration.ofMinutes(10)
    }

    @Transactional
    fun reserve(request: CreateReservationRequest): Reservation {
        val tripId = requireNotNull(request.tripId)
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")

        val sessionId = currentPrincipal()?.jti
        if (sessionId != null) {
            val activeCount = reservationRepository.countActiveBySession(sessionId)
            if (activeCount >= MAX_ACTIVE_RESERVATIONS_PER_SESSION) {
                throw DomainRuleViolation("Session has reached the maximum of $MAX_ACTIVE_RESERVATIONS_PER_SESSION active reservations")
            }
        }

        val inventory = tripInventoryRepository.findByTripId(tripId)
            ?: throw DomainRuleViolation("Trip inventory for $tripId is not initialized yet")

        val segmentMode = segmentOccupancyService.isSegmentMode(tripId)

        seatRepository.findStatusForUpdate(tripId, request.seatNumber)
            ?: throw NoSuchElementException("Seat ${request.seatNumber} was not found")

        seatBlockEnforcer.assertNotBlocked(tripId, request.seatNumber)

        if (!segmentMode) {
            var seatStatus = seatRepository.findStatusForUpdate(tripId, request.seatNumber)!!
            if (seatStatus == SeatStatus.RESERVED) {
                seatStatus = releaseExpiredReservationIfNeeded(tripId, request.seatNumber)
            }
            if (seatStatus != SeatStatus.AVAILABLE) {
                throw DomainRuleViolation("Seat ${request.seatNumber} is not available")
            }
        }

        val now = Clock.systemUTC().instant()
        val fromStop = transitGateEnforcer.resolveFromStopOrder(tripId, request.fromStopOrder)
        val toStop = segmentOccupancyService.resolveToStopOrder(tripId, fromStop, request.toStopOrder)
        if (toStop <= fromStop) {
            throw DomainRuleViolation("toStopOrder must be greater than fromStopOrder")
        }

        salesRestrictionEnforcer.assertSeatAllowed(tripId, request.seatNumber, currentPrincipal()?.stationId)
        transitGateEnforcer.assertCanReserve(tripId, request.seatNumber, fromStop)
        segmentOccupancyService.assertNoOverlap(tripId, request.seatNumber, fromStop, toStop)

        val reservation = Reservation(
            id = UUID.randomUUID(),
            tripId = tripId,
            seatNumber = request.seatNumber,
            status = ReservationStatus.ACTIVE,
            expiresAt = now.plus(RESERVATION_TTL),
            createdAt = now,
            sessionId = sessionId,
            fromStopOrder = fromStop,
            toStopOrder = toStop,
            inventoryId = inventory.id,
        )

        if (!segmentMode) {
            seatRepository.updateStatus(tripId, request.seatNumber, SeatStatus.RESERVED)
        }
        reservationRepository.insert(reservation)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.created",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
                "expiresAt" to reservation.expiresAt,
                "sessionId" to reservation.sessionId,
                "fromStopOrder" to fromStop,
                "toStopOrder" to toStop,
            ),
        )

        return reservation
    }

    @Transactional
    fun confirm(reservationId: UUID): Reservation = consume(reservationId)

    @Transactional
    fun release(reservationId: UUID): Reservation = cancel(reservationId)

    @Transactional
    fun consume(reservationId: UUID): Reservation {
        val reservation = reservationRepository.findByIdForUpdate(reservationId)
            ?: throw NoSuchElementException("Reservation $reservationId was not found")

        if (reservation.status != ReservationStatus.ACTIVE) {
            throw DomainRuleViolation("Reservation $reservationId is not active")
        }

        if (reservation.expiresAt.isBefore(Clock.systemUTC().instant())) {
            expireReservation(reservation)
            throw DomainRuleViolation("Reservation $reservationId is expired")
        }

        reservationRepository.updateStatus(reservation.id, ReservationStatus.CONSUMED)
        if (!segmentOccupancyService.isSegmentMode(reservation.tripId)) {
            seatRepository.updateStatus(reservation.tripId, reservation.seatNumber, SeatStatus.SOLD)
        }
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.confirmed",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
            ),
        )

        return reservation.copy(status = ReservationStatus.CONSUMED)
    }

    @Transactional
    fun recordSeatSale(reservation: Reservation, ticketId: UUID) {
        val fromStop = reservation.fromStopOrder ?: return
        val toStop = reservation.toStopOrder ?: return
        seatSaleRepository.insert(
            id = UUID.randomUUID(),
            tripId = reservation.tripId,
            seatNumber = reservation.seatNumber,
            fromStopOrder = fromStop,
            toStopOrder = toStop,
            ticketId = ticketId,
            reservationId = reservation.id,
        )
    }

    @Transactional
    fun cancelActiveReservationsAboveSeat(tripId: UUID, maxSeatNumber: Int) {
        reservationRepository.listActiveAboveSeat(tripId, maxSeatNumber).forEach { reservation ->
            cancelForReaccommodation(reservation)
        }
    }

    @Transactional
    fun cancelForReaccommodation(reservation: Reservation) {
        if (reservation.status != ReservationStatus.ACTIVE) return
        reservationRepository.updateStatus(reservation.id, ReservationStatus.CANCELLED)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.released",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
                "reason" to "vehicle_reaccommodation",
            ),
        )
    }

    @Transactional
    fun cancel(reservationId: UUID): Reservation {
        val reservation = reservationRepository.findByIdForUpdate(reservationId)
            ?: throw NoSuchElementException("Reservation $reservationId was not found")

        if (reservation.status != ReservationStatus.ACTIVE) {
            throw DomainRuleViolation("Reservation $reservationId is not active")
        }

        releaseSeat(reservation)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.released",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
            ),
        )

        return reservation.copy(status = ReservationStatus.EXPIRED)
    }

    @Transactional
    fun releaseSoldSeat(tripId: UUID, seatNumber: Int) {
        seatRepository.updateStatus(tripId, seatNumber, SeatStatus.AVAILABLE)
        outboxEventRepository.append(
            aggregateType = "seat",
            aggregateId = "$tripId:$seatNumber",
            eventType = "inventory.seat.released",
            payload = mapOf(
                "tripId" to tripId,
                "seatNumber" to seatNumber,
            ),
        )
    }

    @Transactional
    fun releaseSeatSale(ticketId: UUID, tripId: UUID, seatNumber: Int) {
        val sale = seatSaleRepository.findByTicketId(ticketId)
        if (sale != null) {
            seatSaleRepository.markRefunded(sale.id)
            maybeReleaseGlobalSeat(sale.tripId, sale.seatNumber)
        } else if (!segmentOccupancyService.isSegmentMode(tripId)) {
            releaseSoldSeat(tripId, seatNumber)
        }
        outboxEventRepository.append(
            aggregateType = "seat",
            aggregateId = "$tripId:$seatNumber",
            eventType = "inventory.seat.released",
            payload = mapOf(
                "tripId" to tripId,
                "seatNumber" to seatNumber,
                "ticketId" to ticketId,
            ),
        )
    }

    @Transactional
    fun expireOverdueReservations() {
        val now = Clock.systemUTC().instant()
        reservationRepository.findExpiredActive(now).forEach { reservation ->
            expireReservation(reservation)
        }
    }

    private fun maybeReleaseGlobalSeat(tripId: UUID, seatNumber: Int) {
        if (segmentOccupancyService.isSegmentMode(tripId)) {
            if (!segmentOccupancyService.hasActiveOccupancy(tripId, seatNumber)) {
                seatRepository.updateStatus(tripId, seatNumber, SeatStatus.AVAILABLE)
            }
        } else {
            releaseSoldSeat(tripId, seatNumber)
        }
    }

    private fun expireReservation(reservation: Reservation) {
        if (reservation.status != ReservationStatus.ACTIVE) return
        releaseSeat(reservation)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.expired",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
            ),
        )
    }

    private fun releaseSeat(reservation: Reservation) {
        reservationRepository.updateStatus(reservation.id, ReservationStatus.EXPIRED)
        if (!segmentOccupancyService.isSegmentMode(reservation.tripId)) {
            seatRepository.updateStatus(reservation.tripId, reservation.seatNumber, SeatStatus.AVAILABLE)
        }
    }

    private fun releaseExpiredReservationIfNeeded(tripId: UUID, seatNumber: Int): SeatStatus {
        val activeReservation = reservationRepository.findActiveBySeatForUpdate(tripId, seatNumber)
            ?: return SeatStatus.RESERVED

        if (activeReservation.expiresAt.isAfter(Clock.systemUTC().instant())) {
            return SeatStatus.RESERVED
        }

        expireReservation(activeReservation)
        return SeatStatus.AVAILABLE
    }
}
