package ru.transora.app.stationagent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentStatusPayload(
    val version: String? = null,
    val mode: String? = null,
)
