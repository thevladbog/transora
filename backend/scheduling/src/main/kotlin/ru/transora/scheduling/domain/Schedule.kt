package ru.transora.scheduling.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class ScheduleType {
    PERMANENT,
    SEASONAL,
    EXCEPTION,
}

data class Schedule(
    val id: UUID,
    val routeId: UUID,
    val name: String,
    val scheduleType: ScheduleType,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val isActive: Boolean,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ScheduleEntry(
    val id: UUID,
    val scheduleId: UUID,
    val tripNumber: String,
    val departureTime: LocalTime,
    val daysOfWeek: List<Int>,
    val defaultVehicleId: UUID?,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class ScheduleWithEntries(
    val schedule: Schedule,
    val entries: List<ScheduleEntry>,
)
