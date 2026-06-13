package ru.transora.app.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Repository
class OutboxEventRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun append(
        aggregateType: String,
        aggregateId: String,
        eventType: String,
        payload: Any,
    ) {
        jdbc.update(
            """
            INSERT INTO app.outbox_events (
                id, aggregate_type, aggregate_id, event_type,
                payload, occurred_at, published_at
            )
            VALUES (?, ?, ?, ?, ?::jsonb, ?, NULL)
            """.trimIndent(),
            UUID.randomUUID(),
            aggregateType,
            aggregateId,
            eventType,
            objectMapper.writeValueAsString(payload),
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }

    fun fetchUnpublished(limit: Int = 50): List<OutboxEvent> =
        jdbc.query(
            """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text, occurred_at
            FROM app.outbox_events
            WHERE published_at IS NULL
            ORDER BY occurred_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ -> rs.toOutboxEvent() },
            limit,
        )

    fun markPublished(eventId: UUID) {
        jdbc.update(
            "UPDATE app.outbox_events SET published_at = ? WHERE id = ?",
            Timestamp.from(Clock.systemUTC().instant()),
            eventId,
        )
    }

    fun countUnpublished(): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM app.outbox_events WHERE published_at IS NULL",
            Int::class.java,
        ) ?: 0

    fun countByEventType(eventType: String): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM app.outbox_events WHERE event_type = ?",
            Int::class.java,
            eventType,
        ) ?: 0

    private fun ResultSet.toOutboxEvent(): OutboxEvent =
        OutboxEvent(
            id = getObject("id", UUID::class.java),
            aggregateType = getString("aggregate_type"),
            aggregateId = getString("aggregate_id"),
            eventType = getString("event_type"),
            payload = getString("payload"),
            occurredAt = getTimestamp("occurred_at").toInstant(),
        )
}
