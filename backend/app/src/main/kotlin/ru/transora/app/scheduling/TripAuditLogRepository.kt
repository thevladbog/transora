package ru.transora.app.scheduling

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

data class TripAuditEntry(
    val id: UUID,
    val tripId: UUID,
    val eventType: String,
    val oldValue: Map<String, Any?>?,
    val newValue: Map<String, Any?>?,
    val changedBy: UUID?,
    val stationId: UUID?,
)

@Repository
class TripAuditLogRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun insert(
        tripId: UUID,
        eventType: String,
        oldValue: Map<String, Any?>? = null,
        newValue: Map<String, Any?>? = null,
        changedBy: UUID? = null,
        stationId: UUID? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO scheduling.trip_audit_log (
                id, trip_id, event_type, old_value, new_value, changed_by, station_id, created_at
            )
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            tripId,
            eventType,
            oldValue?.let { objectMapper.writeValueAsString(it) },
            newValue?.let { objectMapper.writeValueAsString(it) },
            changedBy,
            stationId,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }
}
