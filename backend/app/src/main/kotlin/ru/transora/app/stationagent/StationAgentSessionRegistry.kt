package ru.transora.app.stationagent

import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class StationAgentSessionRegistry {
    private val sessionsByStation = ConcurrentHashMap<UUID, WebSocketSession>()

    fun register(stationId: UUID, session: WebSocketSession) {
        sessionsByStation.put(stationId, session)?.takeIf { it.isOpen }?.close()
    }

    fun unregister(stationId: UUID, session: WebSocketSession) {
        sessionsByStation.computeIfPresent(stationId) { _, current ->
            if (current.id == session.id) null else current
        }
    }

    fun getSession(stationId: UUID): WebSocketSession? =
        sessionsByStation[stationId]?.takeIf { it.isOpen }

    fun connectedStationIds(): Set<UUID> = sessionsByStation.keys.toSet()
}
