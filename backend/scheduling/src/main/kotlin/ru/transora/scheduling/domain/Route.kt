package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class Route(
    val id: UUID,
    val carrierId: UUID,
    val name: String,
    val code: String?,
    val description: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RouteStop(
    val id: UUID,
    val routeId: UUID,
    val stopOrder: Int,
    val stopName: String,
    val stationId: UUID?,
    val isExternal: Boolean,
    val scheduledDurationMin: Int?,
    val dwellTimeMin: Int,
)

data class RouteWithStops(
    val route: Route,
    val stops: List<RouteStop>,
)
