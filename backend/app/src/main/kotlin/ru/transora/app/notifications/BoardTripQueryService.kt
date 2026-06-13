package ru.transora.app.notifications

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class BoardWindow(
    val from: Instant,
    val to: Instant,
)

@Service
class BoardTripQueryService(
    private val boardTripQueryRepository: BoardTripQueryRepository,
    private val boardProperties: BoardProperties,
) {
    fun listForStation(
        stationCode: String,
        windowBeforeMin: Int? = null,
        windowAfterMin: Int? = null,
    ): List<StationBoardTrip> {
        val window = buildWindow(windowBeforeMin, windowAfterMin)
        val rows = boardTripQueryRepository.listStopAwareRows(stationCode) +
            boardTripQueryRepository.listLegacyFlatRows(stationCode)

        return rows
            .map { it.toStationBoardTrip() }
            .filter { !it.displayTime.isBefore(window.from) && !it.displayTime.isAfter(window.to) }
            .sortedBy { it.displayTime }
    }

    fun buildWindow(windowBeforeMin: Int? = null, windowAfterMin: Int? = null): BoardWindow {
        val now = Clock.systemUTC().instant()
        return BoardWindow(
            from = now.minus(Duration.ofMinutes((windowBeforeMin ?: boardProperties.windowBeforeMin).toLong())),
            to = now.plus(Duration.ofMinutes((windowAfterMin ?: boardProperties.windowAfterMin).toLong())),
        )
    }

    private fun BoardTripQueryRow.toStationBoardTrip(): StationBoardTrip {
        val direction = when (stopOrder) {
            firstStopOrder -> BoardDirection.DEPARTURE
            lastStopOrder -> BoardDirection.ARRIVAL
            else -> BoardDirection.TRANSIT
        }
        val displayTime = resolveDisplayTime(direction)
        val legacy = stopId == tripId

        return StationBoardTrip(
            tripId = tripId,
            routeNumber = routeNumber,
            tripNumber = tripNumber ?: routeNumber,
            tripStatus = tripStatus,
            delayMinutes = delayMinutes,
            platform = platform,
            stopId = stopId,
            stopOrder = stopOrder,
            stopName = stopName,
            stopStatus = stopStatus,
            direction = if (legacy) BoardDirection.DEPARTURE else direction,
            displayTime = displayTime,
            destinationLabel = if (direction == BoardDirection.ARRIVAL) firstStopName else lastStopName,
            originLabel = firstStopName,
            legacyFlatTrip = legacy,
        )
    }

    private fun BoardTripQueryRow.resolveDisplayTime(direction: BoardDirection): Instant =
        when (direction) {
            BoardDirection.ARRIVAL -> actualArrival ?: estimatedArrival ?: scheduledArrival
                ?: actualDeparture ?: estimatedDeparture ?: scheduledDeparture
            BoardDirection.DEPARTURE, BoardDirection.TRANSIT -> actualDeparture ?: estimatedDeparture
                ?: scheduledDeparture
        }
}
