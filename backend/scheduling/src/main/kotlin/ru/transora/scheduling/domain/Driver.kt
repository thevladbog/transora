package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

data class Driver(
    val id: UUID,
    val carrierId: UUID,
    val fullName: String,
    val licenseNo: String,
    val phone: String?,
    val isActive: Boolean,
    val createdAt: Instant,
)
