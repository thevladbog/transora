package ru.transora.app.boarding

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.boarding.domain.BoardingScanEvent
import ru.transora.boarding.domain.BoardingStats
import ru.transora.boarding.domain.ScanResult
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class BoardingRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insertScanEvent(event: BoardingScanEvent) {
        jdbc.update(
            """
            INSERT INTO boarding.scan_events (
                id, ticket_id, trip_id, station_id, scanned_by, scan_result, scanned_at, client_event_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            event.id,
            event.ticketId,
            event.tripId,
            event.stationId,
            event.scannedBy,
            event.scanResult.name,
            Timestamp.from(event.scannedAt),
            event.clientEventId,
            Timestamp.from(Instant.now()),
        )
    }

    fun findByClientEventId(clientEventId: String): BoardingScanEvent? =
        jdbc.query(
            "SELECT * FROM boarding.scan_events WHERE client_event_id = ?",
            { rs, _ -> rs.toEvent() },
            clientEventId,
        ).firstOrNull()

    fun findLatestByTicketId(ticketId: UUID): BoardingScanEvent? =
        jdbc.query(
            """
            SELECT * FROM boarding.scan_events
            WHERE ticket_id = ?
            ORDER BY scanned_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toEvent() },
            ticketId,
        ).firstOrNull()

    fun statsForTrip(tripId: UUID): BoardingStats {
        val totalTickets = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.tickets WHERE trip_id = ?",
            Int::class.java,
            tripId,
        ) ?: 0
        val boardedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.tickets WHERE trip_id = ? AND status = 'USED'",
            Int::class.java,
            tripId,
        ) ?: 0
        val refundedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.tickets WHERE trip_id = ? AND status = 'REFUNDED'",
            Int::class.java,
            tripId,
        ) ?: 0
        return BoardingStats(
            tripId = tripId,
            totalTickets = totalTickets,
            boardedCount = boardedCount,
            pendingCount = totalTickets - boardedCount - refundedCount,
            refundedCount = refundedCount,
        )
    }

    fun insertSyncBatch(id: UUID, stationId: UUID, syncedBy: UUID, eventCount: Int) {
        jdbc.update(
            """
            INSERT INTO boarding.sync_batches (id, station_id, synced_by, event_count, synced_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            stationId,
            syncedBy,
            eventCount,
            Timestamp.from(Instant.now()),
        )
    }

    private fun ResultSet.toEvent(): BoardingScanEvent =
        BoardingScanEvent(
            id = getObject("id", UUID::class.java),
            ticketId = getObject("ticket_id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            scannedBy = getObject("scanned_by", UUID::class.java),
            scanResult = ScanResult.valueOf(getString("scan_result")),
            scannedAt = getTimestamp("scanned_at").toInstant(),
            clientEventId = getString("client_event_id"),
        )
}
