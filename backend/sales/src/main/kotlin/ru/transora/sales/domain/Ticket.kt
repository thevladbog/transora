package ru.transora.sales.domain

import java.time.Instant
import java.util.UUID

data class Ticket(
    val id: UUID,
    val reservationId: UUID,
    val shiftId: UUID,
    val tripId: UUID,
    val seatNumber: Int,
    val passengerName: String,
    val priceCents: Long,
    val status: TicketStatus,
    val issuedAt: Instant,
)

enum class TicketStatus {
    ISSUED,
    REFUNDED,
}

data class CashierShift(
    val id: UUID,
    val stationName: String,
    val cashierName: String,
    val status: ShiftStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
)

enum class ShiftStatus {
    OPEN,
    CLOSED,
}
