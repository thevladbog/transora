package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class SeatLayout(
    val id: UUID,
    val name: String,
    val totalSeats: Int,
    val layoutJson: String,
    val createdAt: Instant,
)
