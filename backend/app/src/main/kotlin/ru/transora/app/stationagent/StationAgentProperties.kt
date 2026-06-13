package ru.transora.app.stationagent

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "transora.station-agent")
data class StationAgentProperties(
    val syncHorizonHours: Int = 48,
    val syncWindowBeforeMin: Int = 30,
)
