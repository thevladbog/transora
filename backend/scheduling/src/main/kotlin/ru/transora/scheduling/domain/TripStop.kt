package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

enum class StopStatus {
    UPCOMING,
    ARRIVED,
    DEPARTED,
    SKIPPED,
}

data class TripStop(
    val id: UUID,
    val tripId: UUID,
    val routeStopId: UUID,
    val stopOrder: Int,
    val stopName: String,
    val stationId: UUID?,
    val isExternal: Boolean,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant,
    val estimatedArrival: Instant?,
    val estimatedDeparture: Instant?,
    val actualArrival: Instant?,
    val actualDeparture: Instant?,
    val stopStatus: StopStatus,
    val updatedBy: UUID? = null,
    val updatedAt: Instant,
)
