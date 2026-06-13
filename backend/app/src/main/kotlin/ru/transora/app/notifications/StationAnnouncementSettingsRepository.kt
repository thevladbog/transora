package ru.transora.app.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock

@Repository
class StationAnnouncementSettingsRepository(
    private val jdbc: JdbcTemplate,
) {
    fun isQueuePaused(stationCode: String): Boolean =
        jdbc.query(
            """
            SELECT queue_paused FROM notifications.station_announcement_settings
            WHERE station_code = ?
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("queue_paused") },
            stationCode.uppercase(),
        ).firstOrNull() ?: false

    fun setQueuePaused(stationCode: String, paused: Boolean) {
        jdbc.update(
            """
            INSERT INTO notifications.station_announcement_settings (station_code, queue_paused, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT (station_code)
            DO UPDATE SET queue_paused = EXCLUDED.queue_paused, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            stationCode.uppercase(),
            paused,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }
}
