package ru.transora.app.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class AnnouncementRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(announcement: AnnouncementRow) {
        jdbc.update(
            """
            INSERT INTO notifications.announcement_queue (
                id, station_code, priority, text, trip_id, status, scheduled_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            announcement.id,
            announcement.stationCode,
            announcement.priority,
            announcement.textContent,
            announcement.tripId,
            announcement.status,
            announcement.scheduledAt?.let { Timestamp.from(it) },
            Timestamp.from(announcement.createdAt),
        )
    }

    fun update(announcement: AnnouncementRow) {
        jdbc.update(
            """
            UPDATE notifications.announcement_queue
            SET priority = ?, text = ?, trip_id = ?, status = ?, scheduled_at = ?
            WHERE id = ? AND station_code = ?
            """.trimIndent(),
            announcement.priority,
            announcement.textContent,
            announcement.tripId,
            announcement.status,
            announcement.scheduledAt?.let { Timestamp.from(it) },
            announcement.id,
            announcement.stationCode,
        )
    }

    fun delete(id: UUID, stationCode: String): Int =
        jdbc.update(
            "DELETE FROM notifications.announcement_queue WHERE id = ? AND station_code = ?",
            id,
            stationCode,
        )

    fun findById(id: UUID, stationCode: String): AnnouncementRow? =
        jdbc.query(
            "SELECT * FROM notifications.announcement_queue WHERE id = ? AND station_code = ?",
            { rs, _ -> rs.toRow() },
            id,
            stationCode,
        ).firstOrNull()

    fun listQueue(stationCode: String): List<AnnouncementRow> =
        jdbc.query(
            """
            SELECT * FROM notifications.announcement_queue
            WHERE station_code = ? AND status IN ('QUEUED', 'PLAYING')
            ORDER BY
                CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                COALESCE(scheduled_at, created_at) ASC
            """.trimIndent(),
            { rs, _ -> rs.toRow() },
            stationCode,
        )

    private fun ResultSet.toRow(): AnnouncementRow =
        AnnouncementRow(
            id = getObject("id", UUID::class.java),
            stationCode = getString("station_code"),
            tripId = getObject("trip_id", UUID::class.java),
            priority = getString("priority"),
            textContent = getString("text"),
            status = getString("status"),
            scheduledAt = getTimestamp("scheduled_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class AnnouncementRow(
    val id: UUID,
    val stationCode: String,
    val tripId: UUID?,
    val priority: String,
    val textContent: String,
    val status: String,
    val scheduledAt: Instant?,
    val createdAt: Instant,
)
