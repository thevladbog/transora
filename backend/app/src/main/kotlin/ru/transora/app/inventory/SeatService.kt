package ru.transora.app.inventory

import org.springframework.stereotype.Service
import ru.transora.app.scheduling.TripRepository
import java.util.UUID

@Service
class SeatService(
    private val tripRepository: TripRepository,
    private val seatRepository: SeatRepository,
    private val stationSeatAvailabilityService: StationSeatAvailabilityService,
) {
    fun listSeats(tripId: UUID): List<SeatAvailability> {
        tripRepository.findById(tripId) ?: throw NoSuchElementException("Trip $tripId was not found")
        return seatRepository.listByTrip(tripId)
    }

    fun listSeatsForStation(tripId: UUID, toStopOrder: Int?): StationSeatView =
        stationSeatAvailabilityService.buildStationView(tripId, toStopOrder)
}
