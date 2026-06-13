package ru.transora.app.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.admin.domain.AuditLogEntry
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AuditLogRepository(
    private val jdbc: JdbcTemplate,
) {
    fun append(
        actorId: UUID?,
        stationId: UUID?,
        module: String,
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        detailsJson: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO admin.audit_log (
                id, actor_id, station_id, module, action, entity_type, entity_id, details_json, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            stationId,
            module,
            action,
            entityType,
            entityId,
            detailsJson,
            Timestamp.from(Instant.now()),
        )
    }

    fun list(
        stationId: UUID?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AuditLogEntry> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (stationId != null) {
            conditions += "station_id = ?"
            params += stationId
        }
        if (from != null) {
            conditions += "created_at >= ?"
            params += Timestamp.from(from)
        }
        if (to != null) {
            conditions += "created_at <= ?"
            params += Timestamp.from(to)
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        params += limit
        return jdbc.query(
            """
            SELECT * FROM admin.audit_log
            $where
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toEntry() },
            *params.toTypedArray(),
        )
    }

    fun listAuthAudit(stationId: UUID?, limit: Int): List<AuthAuditRow> {
        val sql = if (stationId != null) {
            """
            SELECT a.id, a.user_id, a.event_type, a.details_json::text, a.created_at
            FROM iam.auth_audit_log a
            JOIN iam.station_assignments sa ON sa.user_id = a.user_id AND sa.is_active = TRUE
            WHERE sa.station_id = ?
            ORDER BY a.created_at DESC
            LIMIT ?
            """
        } else {
            """
            SELECT id, user_id, event_type, details_json::text, created_at
            FROM iam.auth_audit_log
            ORDER BY created_at DESC
            LIMIT ?
            """
        }
        val params: Array<Any> = if (stationId != null) arrayOf(stationId, limit) else arrayOf(limit)
        return jdbc.query(sql, { rs, _ -> rs.toAuthAudit() }, *params)
    }

    private fun ResultSet.toEntry(): AuditLogEntry =
        AuditLogEntry(
            id = getObject("id", UUID::class.java),
            actorId = getObject("actor_id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            module = getString("module"),
            action = getString("action"),
            entityType = getString("entity_type"),
            entityId = getString("entity_id"),
            detailsJson = getString("details_json"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.toAuthAudit(): AuthAuditRow =
        AuthAuditRow(
            id = getObject("id", UUID::class.java),
            userId = getObject("user_id", UUID::class.java),
            eventType = getString("event_type"),
            detailsJson = getString("details_json"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class AuthAuditRow(
    val id: UUID,
    val userId: UUID?,
    val eventType: String,
    val detailsJson: String?,
    val createdAt: Instant,
)
