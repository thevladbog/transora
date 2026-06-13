package ru.transora.shared.domain

import java.time.Instant
import java.util.UUID

interface DomainEvent {
    val id: UUID
    val aggregateType: String
    val aggregateId: String
    val eventType: String
    val occurredAt: Instant
}

