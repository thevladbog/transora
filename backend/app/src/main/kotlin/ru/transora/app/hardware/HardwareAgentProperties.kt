package ru.transora.app.hardware

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "transora.hardware")
data class HardwareAgentProperties(
    val baseUrl: String = "",
    val agentToken: String = "dev-agent-token",
)
