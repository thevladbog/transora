package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class Trip(
    val id: UUID,
    val routeNumber: String,
    val departureStation: String,
    val arrivalStation: String,
    val departureTime: Instant,
    val platform: String?,
    val status: TripStatus,
    val createdAt: Instant,
)

enum class TripStatus {
    PLANNED,
    OPEN,
    DEPARTED,
    ARRIVED,
    CANCELLED,
}

