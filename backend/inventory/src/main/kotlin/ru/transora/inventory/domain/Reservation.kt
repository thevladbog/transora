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
)

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

