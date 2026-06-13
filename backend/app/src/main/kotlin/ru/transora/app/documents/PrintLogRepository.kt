package ru.transora.app.documents

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class PrintType {
    TICKET_PRINT,
    TICKET_REPRINT,
}

data class PrintLogRecord(
    val id: UUID,
    val documentId: UUID,
    val ticketId: UUID,
    val tripId: UUID,
    val printedBy: String,
    val stationCode: String?,
    val posId: String?,
    val printType: PrintType,
    val printedAt: Instant = Instant.now(),
)

@Repository
class PrintLogRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(record: PrintLogRecord) {
        jdbc.update(
            """
            INSERT INTO documents.print_log (
                id, document_id, ticket_id, trip_id, printed_by, station_code, pos_id, print_type, printed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.documentId,
            record.ticketId,
            record.tripId,
            record.printedBy,
            record.stationCode,
            record.posId,
            record.printType.name,
            Timestamp.from(record.printedAt),
        )
    }

    fun countByTicketId(ticketId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM documents.print_log WHERE ticket_id = ?",
            Int::class.java,
            ticketId,
        ) ?: 0

    fun findLatestByTicketId(ticketId: UUID): PrintLogRecord? =
        jdbc.query(
            """
            SELECT id, document_id, ticket_id, trip_id, printed_by, station_code, pos_id, print_type, printed_at
            FROM documents.print_log
            WHERE ticket_id = ?
            ORDER BY printed_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                PrintLogRecord(
                    id = rs.getObject("id", UUID::class.java),
                    documentId = rs.getObject("document_id", UUID::class.java),
                    ticketId = rs.getObject("ticket_id", UUID::class.java),
                    tripId = rs.getObject("trip_id", UUID::class.java),
                    printedBy = rs.getString("printed_by"),
                    stationCode = rs.getString("station_code"),
                    posId = rs.getString("pos_id"),
                    printType = PrintType.valueOf(rs.getString("print_type")),
                    printedAt = rs.getTimestamp("printed_at").toInstant(),
                )
            },
            ticketId,
        ).firstOrNull()
}
