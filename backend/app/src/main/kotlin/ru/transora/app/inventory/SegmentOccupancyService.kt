package ru.transora.app.inventory

import org.springframework.stereotype.Service
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.scheduling.TripStopRepository
import ru.transora.inventory.domain.ReservationStatus
import java.time.Clock
import java.util.UUID

@Service
class SegmentOccupancyService(
    private val tripStopRepository: TripStopRepository,
    private val reservationRepository: ReservationRepository,
    private val seatSaleRepository: SeatSaleRepository,
) {
    fun isSegmentMode(tripId: UUID): Boolean =
        tripStopRepository.listByTripId(tripId).size >= 2

    fun resolveToStopOrder(tripId: UUID, fromStop: Int, requestedToStopOrder: Int?): Int {
        if (requestedToStopOrder != null) return requestedToStopOrder
        if (isSegmentMode(tripId)) {
            return tripStopRepository.listByTripId(tripId).maxOf { it.stopOrder }
        }
        return fromStop + 1
    }

    fun segmentsOverlap(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Boolean =
        aFrom < bTo && bFrom < aTo

    fun assertNoOverlap(tripId: UUID, seatNumber: Int, fromStop: Int, toStop: Int) {
        if (!isSegmentMode(tripId)) return

        val now = Clock.systemUTC().instant()
        reservationRepository.listActiveByTripSeat(tripId, seatNumber).forEach { reservation ->
            if (reservation.expiresAt.isBefore(now)) return@forEach
            val rFrom = reservation.fromStopOrder ?: return@forEach
            val rTo = reservation.toStopOrder ?: return@forEach
            if (segmentsOverlap(fromStop, toStop, rFrom, rTo)) {
                throw DomainRuleViolation("SEGMENT_OCCUPIED")
            }
        }

        seatSaleRepository.listActiveByTripSeat(tripId, seatNumber).forEach { sale ->
            if (segmentsOverlap(fromStop, toStop, sale.fromStopOrder, sale.toStopOrder)) {
                throw DomainRuleViolation("SEGMENT_OCCUPIED")
            }
        }
    }

    fun isSegmentFree(tripId: UUID, seatNumber: Int, fromStop: Int, toStop: Int): Boolean {
        if (!isSegmentMode(tripId)) return true
        return try {
            assertNoOverlap(tripId, seatNumber, fromStop, toStop)
            true
        } catch (_: DomainRuleViolation) {
            false
        }
    }

    fun hasActiveOccupancy(tripId: UUID, seatNumber: Int): Boolean {
        val now = Clock.systemUTC().instant()
        val activeReservation = reservationRepository.listActiveByTripSeat(tripId, seatNumber)
            .any { it.expiresAt.isAfter(now) }
        if (activeReservation) return true
        return seatSaleRepository.countActiveByTripSeat(tripId, seatNumber) > 0
    }
}
