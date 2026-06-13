package ru.transora.app.notifications

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.transora.app.scheduling.TripRepository
import java.util.UUID

@Component
class BoardRefreshCoordinator(
    private val boardTripQueryRepository: BoardTripQueryRepository,
    private val boardStateService: BoardStateService,
    private val tripRepository: TripRepository,
    private val stationAgentEventPublisher: ru.transora.app.stationagent.StationAgentEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun refreshForTrip(tripId: UUID, fallbackStationCode: String? = null, agentEventType: String = "trip.updated") {
        val stationCodes = boardTripQueryRepository.listStationCodesForTrip(tripId).toMutableSet()
        if (stationCodes.isEmpty()) {
            fallbackStationCode?.trim()?.takeIf { it.isNotEmpty() }?.let { stationCodes += it.uppercase() }
            tripRepository.findById(tripId)?.departureStationCode?.let { stationCodes += it.uppercase() }
        }
        if (stationCodes.isEmpty()) {
            log.debug("No station codes to refresh for trip {}", tripId)
            return
        }
        stationCodes.forEach { code ->
            runCatching { boardStateService.refreshForStation(code) }
                .onFailure { ex -> log.warn("Board refresh failed for station {} trip {}: {}", code, tripId, ex.message) }
        }
        stationAgentEventPublisher.notifyStations(stationCodes, tripId, agentEventType)
    }
}
