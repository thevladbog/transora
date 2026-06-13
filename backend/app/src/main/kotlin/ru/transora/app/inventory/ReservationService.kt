package ru.transora.app.inventory

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
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
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    @Transactional
    fun reserve(request: CreateReservationRequest): Reservation {
        val tripId = requireNotNull(request.tripId)
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")

        var seatStatus = seatRepository.findStatusForUpdate(tripId, request.seatNumber)
            ?: throw NoSuchElementException("Seat ${request.seatNumber} was not found")

        if (seatStatus == SeatStatus.RESERVED) {
            seatStatus = releaseExpiredReservationIfNeeded(tripId, request.seatNumber)
        }

        if (seatStatus != SeatStatus.AVAILABLE) {
            throw DomainRuleViolation("Seat ${request.seatNumber} is not available")
        }

        val now = Clock.systemUTC().instant()
        val reservation = Reservation(
            id = UUID.randomUUID(),
            tripId = tripId,
            seatNumber = request.seatNumber,
            status = ReservationStatus.ACTIVE,
            expiresAt = now.plus(Duration.ofMinutes(10)),
            createdAt = now,
        )

        seatRepository.updateStatus(tripId, request.seatNumber, SeatStatus.RESERVED)
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
            ),
        )

        return reservation
    }

    @Transactional
    fun consume(reservationId: UUID): Reservation {
        val reservation = reservationRepository.findByIdForUpdate(reservationId)
            ?: throw NoSuchElementException("Reservation $reservationId was not found")

        if (reservation.status != ReservationStatus.ACTIVE) {
            throw DomainRuleViolation("Reservation $reservationId is not active")
        }

        if (reservation.expiresAt.isBefore(Clock.systemUTC().instant())) {
            reservationRepository.updateStatus(reservation.id, ReservationStatus.EXPIRED)
            seatRepository.updateStatus(reservation.tripId, reservation.seatNumber, SeatStatus.AVAILABLE)
            throw DomainRuleViolation("Reservation $reservationId is expired")
        }

        reservationRepository.updateStatus(reservation.id, ReservationStatus.CONSUMED)
        seatRepository.updateStatus(reservation.tripId, reservation.seatNumber, SeatStatus.SOLD)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = reservation.id.toString(),
            eventType = "reservation.consumed",
            payload = mapOf(
                "reservationId" to reservation.id,
                "tripId" to reservation.tripId,
                "seatNumber" to reservation.seatNumber,
            ),
        )

        return reservation.copy(status = ReservationStatus.CONSUMED)
    }

    private fun releaseExpiredReservationIfNeeded(tripId: UUID, seatNumber: Int): SeatStatus {
        val activeReservation = reservationRepository.findActiveBySeatForUpdate(tripId, seatNumber)
            ?: return SeatStatus.RESERVED

        if (activeReservation.expiresAt.isAfter(Clock.systemUTC().instant())) {
            return SeatStatus.RESERVED
        }

        reservationRepository.updateStatus(activeReservation.id, ReservationStatus.EXPIRED)
        seatRepository.updateStatus(tripId, seatNumber, SeatStatus.AVAILABLE)
        outboxEventRepository.append(
            aggregateType = "reservation",
            aggregateId = activeReservation.id.toString(),
            eventType = "reservation.expired",
            payload = mapOf(
                "reservationId" to activeReservation.id,
                "tripId" to tripId,
                "seatNumber" to seatNumber,
            ),
        )

        return SeatStatus.AVAILABLE
    }
}
