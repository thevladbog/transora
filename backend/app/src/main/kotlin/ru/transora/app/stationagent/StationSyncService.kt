package ru.transora.app.stationagent

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import ru.transora.app.notifications.BoardTripQueryService
import ru.transora.app.scheduling.ServiceStationRepository
import ru.transora.app.scheduling.TripStopRepository
import java.time.Instant
import java.util.UUID

@Service
class StationSyncService(
    private val serviceStationRepository: ServiceStationRepository,
    private val boardTripQueryService: BoardTripQueryService,
    private val tripStopRepository: TripStopRepository,
    private val properties: StationAgentProperties,
    private val objectMapper: ObjectMapper,
) {
    fun buildSnapshot(stationId: UUID, horizonHours: Int? = null): SyncSnapshotPayload {
        val station = serviceStationRepository.findById(stationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown station")
        val horizon = horizonHours ?: properties.syncHorizonHours
        val windowAfterMin = horizon * 60
        val trips = boardTripQueryService
            .listForStation(station.code, properties.syncWindowBeforeMin, windowAfterMin)
            .distinctBy { it.tripId }
        val version = Instant.now().toEpochMilli()
        val payloads = trips.map { boardTrip ->
            val stops = tripStopRepository.listByTripId(boardTrip.tripId)
            boardTrip.toSyncTripPayload(stops, version)
        }
        return SyncSnapshotPayload(
            stationId = stationId.toString(),
            generatedAt = Instant.now().toString(),
            version = version,
            trips = payloads,
        )
    }

    fun buildTripPayload(stationId: UUID, tripId: UUID): SyncTripPayload? {
        val station = serviceStationRepository.findById(stationId) ?: return null
        val horizon = properties.syncHorizonHours
        val boardTrip = boardTripQueryService
            .listForStation(station.code, properties.syncWindowBeforeMin, horizon * 60)
            .firstOrNull { it.tripId == tripId }
            ?: return null
        val stops = tripStopRepository.listByTripId(tripId)
        val version = Instant.now().toEpochMilli()
        return boardTrip.toSyncTripPayload(stops, version)
    }

    fun snapshotMessage(stationId: UUID, horizonHours: Int? = null): String {
        val snapshot = buildSnapshot(stationId, horizonHours)
        return objectMapper.writeStationAgentMessage("sync.snapshot", snapshot)
    }

    fun tripEventMessage(eventType: String, payload: SyncTripPayload): String =
        objectMapper.writeStationAgentMessage(eventType, payload)

    fun ticketStatusMessage(eventType: String, payload: TicketStatusPayload): String =
        objectMapper.writeStationAgentMessage(eventType, payload)
}
