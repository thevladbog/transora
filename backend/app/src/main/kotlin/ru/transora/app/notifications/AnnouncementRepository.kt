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
                id, station_code, priority, text, trip_id, status, scheduled_at, created_at,
                audio_path, template_code
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            announcement.id,
            announcement.stationCode,
            announcement.priority,
            announcement.textContent,
            announcement.tripId,
            announcement.status,
            announcement.scheduledAt?.let { Timestamp.from(it) },
            Timestamp.from(announcement.createdAt),
            announcement.audioPath,
            announcement.templateCode,
        )
    }

    fun update(announcement: AnnouncementRow) {
        jdbc.update(
            """
            UPDATE notifications.announcement_queue
            SET priority = ?, text = ?, trip_id = ?, status = ?, scheduled_at = ?,
                audio_path = ?, template_code = ?
            WHERE id = ? AND station_code = ?
            """.trimIndent(),
            announcement.priority,
            announcement.textContent,
            announcement.tripId,
            announcement.status,
            announcement.scheduledAt?.let { Timestamp.from(it) },
            announcement.audioPath,
            announcement.templateCode,
            announcement.id,
            announcement.stationCode,
        )
    }

    fun updateAudioPath(id: UUID, audioPath: String) {
        jdbc.update(
            "UPDATE notifications.announcement_queue SET audio_path = ? WHERE id = ?",
            audioPath,
            id,
        )
    }

    fun updateStatus(id: UUID, status: String) {
        jdbc.update(
            "UPDATE notifications.announcement_queue SET status = ? WHERE id = ?",
            status,
            id,
        )
    }

    fun delete(id: UUID, stationCode: String): Int =
        jdbc.update(
            "DELETE FROM notifications.announcement_queue WHERE id = ? AND station_code = ?",
            id,
            stationCode,
        )

    fun findById(id: UUID): AnnouncementRow? =
        jdbc.query(
            "SELECT * FROM notifications.announcement_queue WHERE id = ?",
            { rs, _ -> rs.toRow() },
            id,
        ).firstOrNull()

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

    fun listDue(now: Instant, limit: Int = 20): List<AnnouncementRow> =
        jdbc.query(
            """
            SELECT aq.*
            FROM notifications.announcement_queue aq
            LEFT JOIN notifications.station_announcement_settings sas
                ON sas.station_code = aq.station_code
            WHERE aq.status = 'QUEUED'
              AND COALESCE(aq.scheduled_at, aq.created_at) <= ?
              AND COALESCE(sas.queue_paused, FALSE) = FALSE
            ORDER BY
                CASE aq.priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                COALESCE(aq.scheduled_at, aq.created_at) ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toRow() },
            Timestamp.from(now),
            limit,
        )

    fun recordScheduled(stationCode: String, tripId: UUID, templateCode: String): Boolean {
        val inserted = jdbc.update(
            """
            INSERT INTO notifications.scheduled_announcement_log (id, station_code, trip_id, template_code)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (station_code, trip_id, template_code) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(),
            stationCode.uppercase(),
            tripId,
            templateCode,
        )
        return inserted > 0
    }

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
            audioPath = getString("audio_path"),
            templateCode = getString("template_code"),
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
    val audioPath: String? = null,
    val templateCode: String? = null,
)
