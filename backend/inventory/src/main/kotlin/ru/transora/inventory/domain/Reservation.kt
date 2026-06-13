package ru.transora.inventory.domain

import java.time.Instant
import java.util.UUID

data class Reservation(
    val id: UUID,
    val tripId: UUID,
    val seatNumber: Int,
    val status: ReservationStatus,
    val expiresAt: Instant,
    val createdAt: Instant,
    val sessionId: String? = null,
    val fromStopOrder: Int? = null,
    val toStopOrder: Int? = null,
    val inventoryId: UUID? = null,
)

data class TripInventory(
    val id: UUID,
    val tripId: UUID,
    val totalSeats: Int,
    val status: TripInventoryStatus,
    val createdAt: Instant,
)

enum class TripInventoryStatus {
    INITIALIZING,
    ACTIVE,
    FROZEN,
    REACCOMMODATING,
}

enum class ReservationStatus {
    ACTIVE,
    CONSUMED,
    EXPIRED,
    CANCELLED,
}

enum class SeatStatus {
    AVAILABLE,
    RESERVED,
    SOLD,
}

