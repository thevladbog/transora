package ru.transora.boarding.domain

import java.time.Instant
import java.util.UUID

enum class ScanResult {
    BOARDED,
    ALREADY_USED,
    INVALID_TICKET,
    WRONG_TRIP,
    REFUNDED,
}

data class BoardingScanEvent(
    val id: UUID,
    val ticketId: UUID,
    val tripId: UUID,
    val stationId: UUID,
    val scannedBy: UUID,
    val scanResult: ScanResult,
    val scannedAt: Instant,
    val clientEventId: String?,
)

data class BoardingStats(
    val tripId: UUID,
    val totalTickets: Int,
    val boardedCount: Int,
    val pendingCount: Int,
    val refundedCount: Int,
)
