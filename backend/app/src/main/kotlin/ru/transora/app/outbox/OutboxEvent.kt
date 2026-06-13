package ru.transora.app.outbox

import java.time.Instant
import java.util.UUID

data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val occurredAt: Instant,
)
