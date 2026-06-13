package ru.transora.app.scheduling

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.inventory.SeatRepository
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.scheduling.domain.Trip
import ru.transora.scheduling.domain.TripStatus
import java.time.Clock
import java.util.UUID

@Service
class TripService(
    private val tripRepository: TripRepository,
    private val seatRepository: SeatRepository,
    private val outboxEventRepository: OutboxEventRepository,
) {
    @Transactional
    fun createTrip(request: CreateTripRequest): Trip {
        val now = Clock.systemUTC().instant()
        val trip = Trip(
            id = UUID.randomUUID(),
            routeNumber = request.routeNumber.trim(),
            departureStation = request.departureStation.trim(),
            arrivalStation = request.arrivalStation.trim(),
            departureTime = request.departureTime,
            platform = request.platform?.trim()?.takeIf { it.isNotEmpty() },
            status = TripStatus.OPEN,
            createdAt = now,
        )

        tripRepository.insert(trip)
        seatRepository.createSeats(trip.id, request.seatCount)
        outboxEventRepository.append(
            aggregateType = "trip",
            aggregateId = trip.id.toString(),
            eventType = "trip.created",
            payload = mapOf(
                "tripId" to trip.id,
                "routeNumber" to trip.routeNumber,
                "departureStation" to trip.departureStation,
                "arrivalStation" to trip.arrivalStation,
                "departureTime" to trip.departureTime,
                "platform" to trip.platform,
                "seatCount" to request.seatCount,
            ),
        )

        return trip
    }

    fun listTrips(): List<Trip> =
        tripRepository.list()
}
