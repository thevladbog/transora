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

    fun isAgentConnected(stationId: UUID): Boolean =
        sessionRegistry.getSession(stationId) != null

    fun sendSyncForce(stationId: UUID): Boolean {
        val session = sessionRegistry.getSession(stationId) ?: return false
        send(session, syncService.syncForceMessage(stationId))
        return true
    }

    fun sendPing(stationId: UUID) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, """{"type":"ping","payload":{"ts":"${Instant.now()}"}}""")
    }

    fun notifyTicketUsed(stationId: UUID, payload: TicketStatusPayload) {
        notifyTicketEvent(stationId, "ticket.used", payload)
    }

    fun notifyTicketEvent(stationId: UUID, eventType: String, payload: TicketStatusPayload) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.ticketStatusMessage(eventType, payload))
    }

    fun playAudio(stationId: UUID, payload: AudioPlayPayload) {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.audioPlayMessage(payload))
    }

    fun stopAudio(stationId: UUID, reason: String = "queue_paused") {
        val session = sessionRegistry.getSession(stationId) ?: return
        send(session, syncService.audioStopMessage(AudioStopPayload(reason = reason)))
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
