package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class SeatBlockRepository(
    private val jdbc: JdbcTemplate,
) {
    fun isBlocked(tripId: UUID, seatNumber: Int): Boolean =
        (jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory.seat_blocks
            WHERE trip_id = ? AND seat_number = ?
              AND released_at IS NULL
              AND (expires_at IS NULL OR expires_at > NOW())
            """.trimIndent(),
            Int::class.java,
            tripId,
            seatNumber,
        ) ?: 0) > 0
}
