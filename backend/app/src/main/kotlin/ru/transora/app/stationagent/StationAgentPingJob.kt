package ru.transora.app.stationagent

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class StationAgentPingJob(
    private val properties: StationAgentProperties,
    private val sessionRegistry: StationAgentSessionRegistry,
    private val stationAgentEventPublisher: StationAgentEventPublisher,
) {
    @Scheduled(fixedDelayString = "\${transora.station-agent.ping-interval-ms:30000}")
    fun pingConnectedAgents() {
        if (!properties.pingEnabled) {
            return
        }
        sessionRegistry.connectedStationIds().forEach { stationId ->
            stationAgentEventPublisher.sendPing(stationId)
        }
    }
}
