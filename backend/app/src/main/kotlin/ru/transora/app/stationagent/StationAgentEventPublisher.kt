package ru.transora.app.stationagent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import ru.transora.app.scheduling.ServiceStationRepository
import java.time.Instant
import java.util.UUID

@Component
class StationAgentEventPublisher(
    private val sessionRegistry: StationAgentSessionRegistry,
    private val syncService: StationSyncService,
    private val serviceStationRepository: ServiceStationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun notifyStations(stationCodes: Collection<String>, tripId: UUID, eventType: String) {
        stationCodes.mapNotNull { code ->
            serviceStationRepository.findByCode(code.trim().uppercase())?.id?.let { stationId -> stationId }
        }.distinct().forEach { stationId ->
            notifyStation(stationId, tripId, eventType)
        }
    }

    fun notifyStation(stationId: UUID, tripId: UUID, eventType: String) {
        val session = sessionRegistry.getSession(stationId) ?: return
        val payload = syncService.buildTripPayload(stationId, tripId)
        if (payload == null) {
            if (eventType == "trip.cancelled") {
                send(session, syncService.tripEventMessage(eventType, cancelledPayload(tripId)))
            }
            return
        }
        send(session, syncService.tripEventMessage(eventType, payload))
    }

    fun sendSyncForce(stationId: UUID) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.snapshotMessage(stationId))
    }

    fun sendPing(stationId: UUID) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, """{"type":"ping","payload":{"ts":"${Instant.now()}"}}""")
    }

    fun notifyTicketUsed(stationId: UUID, payload: TicketStatusPayload) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.ticketStatusMessage("ticket.used", payload))
    }

    fun playAudio(stationId: UUID, payload: AudioPlayPayload) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.audioPlayMessage(payload))
    }

    private fun send(session: WebSocketSession, json: String) {
        runCatching {
            if (session.isOpen) {
                session.sendMessage(TextMessage(json))
            }
        }.onFailure { ex ->
            log.warn("Failed to send station agent message: {}", ex.message)
        }
    }

    private fun cancelledPayload(tripId: UUID) =
        SyncTripPayload(
            tripId = tripId.toString(),
            tripNumber = "",
            tripDate = "",
            routeName = "",
            status = "CANCELLED",
            delayMinutes = 0,
            displayTime = "",
            directionStop = "",
            version = Instant.now().toEpochMilli(),
        )
}
