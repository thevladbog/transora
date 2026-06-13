package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.SeatLayout
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SeatLayoutRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(layout: SeatLayout) {
        jdbc.update(
            """
            INSERT INTO scheduling.seat_layouts (id, name, total_seats, layout_json, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """.trimIndent(),
            layout.id,
            layout.name,
            layout.totalSeats,
            layout.layoutJson,
            Timestamp.from(layout.createdAt),
        )
    }

    fun findById(id: UUID): SeatLayout? =
        jdbc.query(
            "SELECT * FROM scheduling.seat_layouts WHERE id = ?",
            { rs, _ -> rs.toSeatLayout() },
            id,
        ).firstOrNull()

    fun list(): List<SeatLayout> =
        jdbc.query("SELECT * FROM scheduling.seat_layouts ORDER BY name") { rs, _ -> rs.toSeatLayout() }

    private fun ResultSet.toSeatLayout(): SeatLayout =
        SeatLayout(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            totalSeats = getInt("total_seats"),
            layoutJson = getString("layout_json"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
