package ru.transora.app.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DisplayBoardRecord(
    val id: UUID,
    val stationCode: String,
    val boardType: String,
    val platformNumber: String?,
    val name: String,
    val isActive: Boolean,
    val agentId: String? = null,
    val lastSeenAt: Instant? = null,
)

@Repository
class DisplayBoardRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listActiveByStation(stationCode: String): List<DisplayBoardRecord> =
        jdbc.query(
            """
            SELECT id, station_code, board_type, platform_number, name, is_active, agent_id, last_seen_at
            FROM notifications.display_boards
            WHERE station_code = ? AND is_active = TRUE
            ORDER BY board_type, platform_number NULLS FIRST, name
            """.trimIndent(),
            { rs, _ -> rs.toBoard() },
            stationCode.uppercase(),
        )

    fun findById(id: UUID): DisplayBoardRecord? =
        jdbc.query(
            """
            SELECT id, station_code, board_type, platform_number, name, is_active, agent_id, last_seen_at
            FROM notifications.display_boards
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.toBoard() },
            id,
        ).firstOrNull()

    fun findById(id: UUID, stationCode: String): DisplayBoardRecord? =
        jdbc.query(
            """
            SELECT id, station_code, board_type, platform_number, name, is_active, agent_id, last_seen_at
            FROM notifications.display_boards
            WHERE id = ? AND station_code = ?
            """.trimIndent(),
            { rs, _ -> rs.toBoard() },
            id,
            stationCode.uppercase(),
        ).firstOrNull()

    fun findByAgentId(agentId: String): DisplayBoardRecord? =
        jdbc.query(
            """
            SELECT id, station_code, board_type, platform_number, name, is_active, agent_id, last_seen_at
            FROM notifications.display_boards
            WHERE agent_id = ?
            """.trimIndent(),
            { rs, _ -> rs.toBoard() },
            agentId,
        ).firstOrNull()

    fun insert(board: DisplayBoardRecord) {
        val now = Timestamp.from(Clock.systemUTC().instant())
        jdbc.update(
            """
            INSERT INTO notifications.display_boards (
                id, station_code, board_type, platform_number, name, is_active, agent_id, last_seen_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            board.id,
            board.stationCode.uppercase(),
            board.boardType,
            board.platformNumber,
            board.name,
            board.isActive,
            board.agentId,
            board.lastSeenAt?.let { Timestamp.from(it) } ?: now,
            now,
        )
    }

    fun touchLastSeen(id: UUID) {
        jdbc.update(
            "UPDATE notifications.display_boards SET last_seen_at = ? WHERE id = ?",
            Timestamp.from(Clock.systemUTC().instant()),
            id,
        )
    }

    fun saveSnapshot(boardId: UUID, payloadJson: String) {
        jdbc.update(
            """
            INSERT INTO notifications.board_snapshots (id, board_id, payload_json, created_at)
            VALUES (?, ?, ?::jsonb, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            boardId,
            payloadJson,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }

    private fun ResultSet.toBoard(): DisplayBoardRecord =
        DisplayBoardRecord(
            id = getObject("id", UUID::class.java),
            stationCode = getString("station_code"),
            boardType = getString("board_type"),
            platformNumber = getString("platform_number"),
            name = getString("name"),
            isActive = getBoolean("is_active"),
            agentId = getString("agent_id"),
            lastSeenAt = getTimestamp("last_seen_at")?.toInstant(),
        )
}
