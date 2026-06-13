package ru.transora.scheduling.domain

import java.time.Instant
import java.util.UUID

enum class ContractType {
    ROUTE_RENT,
    SERVICE_FEE,
}

data class Carrier(
    val id: UUID,
    val name: String,
    val legalName: String,
    val inn: String,
    val contractType: ContractType,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
