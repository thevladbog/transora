package ru.transora.app.inventory

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.inventory.domain.TripInventory
import ru.transora.inventory.domain.TripInventoryStatus
import ru.transora.inventory.domain.SeatStatus
import java.time.Clock
import java.util.UUID

@Service
class TripInventoryService(
    private val tripInventoryRepository: TripInventoryRepository,
    private val seatRepository: SeatRepository,
    private val seatSaleRepository: SeatSaleRepository,
    private val reservationService: ReservationService,
    private val inventoryEventPublisher: InventoryEventPublisher,
) {
    @Transactional
    fun initializeForTrip(tripId: UUID, seatCount: Int, stationId: UUID? = null): TripInventory {
        if (tripInventoryRepository.existsByTripId(tripId)) {
            return tripInventoryRepository.findByTripId(tripId)
                ?: throw IllegalStateException("Trip inventory marker exists without row for trip $tripId")
        }

        val now = Clock.systemUTC().instant()
        val inventory = TripInventory(
            id = UUID.randomUUID(),
            tripId = tripId,
            totalSeats = seatCount,
            status = TripInventoryStatus.ACTIVE,
            createdAt = now,
        )
        tripInventoryRepository.insert(inventory)
        seatRepository.createSeats(tripId, seatCount)
        return inventory
    }

    @Transactional
    fun reaccommodateForTrip(tripId: UUID, newSeatCount: Int): ReaccommodationResult {
        val inventory = tripInventoryRepository.findByTripId(tripId)
        if (inventory == null) {
            initializeForTrip(tripId, newSeatCount)
            return ReaccommodationResult(emptyList())
        }
        if (inventory.totalSeats == newSeatCount) {
            return ReaccommodationResult(emptyList())
        }

        tripInventoryRepository.updateStatus(tripId, TripInventoryStatus.REACCOMMODATING)
        reservationService.cancelActiveReservationsAboveSeat(tripId, newSeatCount)

        val soldSeatNumbers = resolveSoldSeatNumbers(tripId)
        val result = seatRepository.reaccommodateSeats(tripId, newSeatCount, soldSeatNumbers)
        tripInventoryRepository.updateTotalSeats(tripId, newSeatCount)
        tripInventoryRepository.updateStatus(tripId, TripInventoryStatus.ACTIVE)

        if (result.reaccommodationSeatNumbers.isNotEmpty()) {
            inventoryEventPublisher.publishReaccommodationRequired(tripId, result.reaccommodationSeatNumbers)
        }

        return result
    }

    private fun resolveSoldSeatNumbers(tripId: UUID): Set<Int> {
        val fromSeatStatus = seatRepository.listByTrip(tripId)
            .filter { it.status == SeatStatus.SOLD }
            .map { it.seatNumber }
        val fromSeatSales = seatSaleRepository.listActiveByTrip(tripId).map { it.seatNumber }
        return (fromSeatStatus + fromSeatSales).toSet()
    }
}
