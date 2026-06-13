package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class Vehicle(
    val id: UUID,
    val carrierId: UUID,
    val model: String,
    val plateNumber: String,
    val seatLayoutId: UUID,
    val totalSeats: Int,
    val year: Int?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
