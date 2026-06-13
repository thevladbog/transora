package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class ServiceStation(
    val id: UUID,
    val code: String,
    val name: String,
    val city: String,
    val timezone: String,
    val address: String?,
    val isActive: Boolean,
    val createdAt: Instant,
)
