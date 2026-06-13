package ru.transora.app.notifications

import ru.transora.scheduling.domain.BoardDisplayStatus
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.TripStatus
import java.time.Instant
import java.util.UUID

enum class BoardDirection {
    DEPARTURE,
    ARRIVAL,
    TRANSIT,
}

data class StationBoardTrip(
    val tripId: UUID,
    val routeNumber: String,
    val tripNumber: String,
    val tripStatus: TripStatus,
    val delayMinutes: Int?,
    val platform: String?,
    val stopId: UUID,
    val stopOrder: Int,
    val stopName: String,
    val stopStatus: StopStatus,
    val direction: BoardDirection,
    val displayTime: Instant,
    val destinationLabel: String,
    val originLabel: String,
    val legacyFlatTrip: Boolean = false,
) {
    fun matchesDeparturesBoard(): Boolean {
        if (tripStatus == TripStatus.CANCELLED || tripStatus == TripStatus.COMPLETED) return false
        if (direction !in setOf(BoardDirection.DEPARTURE, BoardDirection.TRANSIT)) return false
        if (stopStatus == StopStatus.DEPARTED || stopStatus == StopStatus.SKIPPED) return false
        if (tripStatus == TripStatus.ARRIVED && direction == BoardDirection.DEPARTURE) return false
        return true
    }

    fun matchesArrivalsBoard(): Boolean {
        if (tripStatus == TripStatus.CANCELLED || tripStatus == TripStatus.COMPLETED) return false
        return when {
            direction == BoardDirection.ARRIVAL -> stopStatus != StopStatus.DEPARTED
            direction == BoardDirection.TRANSIT && tripStatus == TripStatus.IN_TRANSIT -> true
            direction == BoardDirection.TRANSIT &&
                tripStatus == TripStatus.DEPARTED &&
                stopStatus == StopStatus.UPCOMING -> true
            direction == BoardDirection.TRANSIT && stopStatus == StopStatus.ARRIVED -> true
            else -> false
        }
    }

    fun toDepartureRow(): DepartureRow =
        DepartureRow(
            tripId = tripId.toString(),
            time = displayTime,
            displayTime = displayTime,
            destination = destinationLabel,
            route = routeNumber,
            tripNumber = tripNumber,
            platform = platform?.let { "Platform $it" },
            displayStatus = boardDisplayStatus(forArrivals = false),
            delayMinutes = delayMinutes,
            direction = direction.name,
            stopOrder = stopOrder,
        )

    fun toArrivalRow(): ArrivalRow =
        ArrivalRow(
            tripId = tripId.toString(),
            time = displayTime,
            displayTime = displayTime,
            origin = originLabel,
            route = routeNumber,
            tripNumber = tripNumber,
            platform = platform?.let { "Platform $it" },
            displayStatus = boardDisplayStatus(forArrivals = true),
            delayMinutes = delayMinutes,
            direction = direction.name,
            stopOrder = stopOrder,
        )

    private fun boardDisplayStatus(forArrivals: Boolean): BoardDisplayStatus =
        ru.transora.scheduling.domain.TripBoardProjection.displayStatusForStop(
            tripStatus = tripStatus,
            stopStatus = stopStatus,
            delayMinutes = delayMinutes,
            forArrivalsBoard = forArrivals,
        )
}
