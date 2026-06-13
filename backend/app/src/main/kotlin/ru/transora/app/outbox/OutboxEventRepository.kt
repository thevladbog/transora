package ru.transora.app.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
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
}

