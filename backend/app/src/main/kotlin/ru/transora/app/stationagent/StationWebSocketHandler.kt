package ru.transora.app.stationagent

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID

@Component
class StationWebSocketHandler(
    private val sessionRegistry: StationAgentSessionRegistry,
    private val syncService: StationSyncService,
    private val objectMapper: ObjectMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val stationId = session.attributes[StationWebSocketHandshakeInterceptor.ATTR_STATION_ID] as? UUID
            ?: run {
                session.close(CloseStatus.BAD_DATA)
                return
            }
        sessionRegistry.register(stationId, session)
        log.info("Station agent connected for station {}", stationId)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val stationId = session.attributes[StationWebSocketHandshakeInterceptor.ATTR_STATION_ID] as? UUID
            ?: return
        val envelope = runCatching {
            objectMapper.readValue(message.payload, StationAgentMessage::class.java)
        }.getOrElse {
            log.warn("Invalid station agent message: {}", it.message)
            return
        }
        when (envelope.type) {
            "sync.request" -> handleSyncRequest(session, stationId, envelope.payload)
            "pong", "agent.status" -> Unit
            else -> log.debug("Ignoring station agent message type {}", envelope.type)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val stationId = session.attributes[StationWebSocketHandshakeInterceptor.ATTR_STATION_ID] as? UUID
            ?: return
        sessionRegistry.unregister(stationId, session)
        log.info("Station agent disconnected for station {} ({})", stationId, status)
    }

    private fun handleSyncRequest(session: WebSocketSession, stationId: UUID, payload: Any) {
        val request = objectMapper.convertValue(payload, SyncRequestPayload::class.java)
        val horizon = request.horizonHours
        val json = syncService.snapshotMessage(stationId, horizon)
        session.sendMessage(TextMessage(json))
    }
}
