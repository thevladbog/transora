package ru.transora.app.outbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Repository
class ProcessedEventRepository(
    private val jdbc: JdbcTemplate,
) {
    fun isProcessed(eventId: UUID): Boolean {
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM app.processed_events WHERE event_id = ?",
            Int::class.java,
            eventId,
        ) ?: 0
        return count > 0
    }

    fun markProcessed(eventId: UUID) {
        jdbc.update(
            """
            INSERT INTO app.processed_events (event_id, processed_at)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """.trimIndent(),
            eventId,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }
}
