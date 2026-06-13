package ru.transora.app.notifications

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import ru.transora.app.stationagent.StationAgentProperties
import ru.transora.app.stationagent.StationWebSocketHandler
import ru.transora.app.stationagent.StationWebSocketHandshakeInterceptor

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(StationAgentProperties::class)
class WebSocketConfig(
    private val boardWebSocketHandler: BoardWebSocketHandler,
    private val stationWebSocketHandler: StationWebSocketHandler,
    private val stationWebSocketHandshakeInterceptor: StationWebSocketHandshakeInterceptor,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(boardWebSocketHandler, "/ws/board/{boardId}")
            .setAllowedOrigins("*")
        registry.addHandler(stationWebSocketHandler, "/ws/stations")
            .addInterceptors(stationWebSocketHandshakeInterceptor)
            .setAllowedOrigins("*")
    }
}
