package ru.transora.admin.domain

import java.time.Instant
import java.util.UUID

data class StationRevenueReport(
    val stationId: UUID?,
    val from: Instant,
    val to: Instant,
    val ticketsSold: Int,
    val grossRevenueCents: Long,
    val refundsCount: Int,
    val refundsCents: Long,
    val netRevenueCents: Long,
)

data class PassengerFlowReport(
    val stationId: UUID?,
    val from: Instant,
    val to: Instant,
    val tripsCount: Int,
    val passengersIssued: Int,
    val passengersBoarded: Int,
    val passengersRefunded: Int,
)

data class AuditLogEntry(
    val id: UUID,
    val actorId: UUID?,
    val stationId: UUID?,
    val module: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val detailsJson: String?,
    val createdAt: Instant,
)
