package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Ticket
import ru.transora.sales.domain.TicketStatus
import ru.transora.sales.domain.DocType
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID

@Repository
class TicketRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(ticket: Ticket) {
        jdbc.update(
            """
            INSERT INTO sales.tickets (
                id, ticket_number, reservation_id, shift_id, trip_id, seat_number,
                passenger_name, price_cents, status, issued_at, order_id, doc_type, doc_number
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ticket.id,
            ticket.ticketNumber,
            ticket.reservationId,
            ticket.shiftId,
            ticket.tripId,
            ticket.seatNumber,
            ticket.passengerName,
            ticket.priceCents,
            ticket.status.name,
            Timestamp.from(ticket.issuedAt),
            ticket.orderId,
            ticket.docType?.name,
            ticket.docNumber,
        )
    }

    fun findById(id: UUID): Ticket? =
        jdbc.query(
            "SELECT * FROM sales.tickets WHERE id = ?",
            { rs, _ -> rs.toTicket() },
            id,
        ).firstOrNull()

    fun findByIdForUpdate(id: UUID): Ticket? =
        jdbc.query(
            "SELECT * FROM sales.tickets WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.toTicket() },
            id,
        ).firstOrNull()

    fun findByTicketNumber(ticketNumber: String): Ticket? =
        jdbc.query(
            "SELECT * FROM sales.tickets WHERE ticket_number = ?",
            { rs, _ -> rs.toTicket() },
            ticketNumber,
        ).firstOrNull()

    fun updateStatus(id: UUID, status: TicketStatus) {
        jdbc.update(
            "UPDATE sales.tickets SET status = ? WHERE id = ?",
            status.name,
            id,
        )
    }

    fun listByTripId(tripId: UUID): List<Ticket> =
        jdbc.query(
            "SELECT * FROM sales.tickets WHERE trip_id = ? ORDER BY issued_at",
            { rs, _ -> rs.toTicket() },
            tripId,
        )

    fun listActiveByTripId(tripId: UUID): List<Ticket> =
        jdbc.query(
            """
            SELECT * FROM sales.tickets
            WHERE trip_id = ? AND status IN (?, ?)
            ORDER BY seat_number
            """.trimIndent(),
            { rs, _ -> rs.toTicket() },
            tripId,
            TicketStatus.ISSUED.name,
            TicketStatus.USED.name,
        )

    fun countIssuedByTripId(tripId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM sales.tickets
            WHERE trip_id = ? AND status = ?
            """.trimIndent(),
            Int::class.java,
            tripId,
            TicketStatus.ISSUED.name,
        ) ?: 0

    fun nextDailySequence(stationCode: String, issueDate: LocalDate): Long {
        jdbc.update(
            """
            INSERT INTO sales.ticket_daily_sequences (station_code, issue_date, last_value)
            VALUES (?, ?, 1)
            ON CONFLICT (station_code, issue_date)
            DO UPDATE SET last_value = sales.ticket_daily_sequences.last_value + 1
            """.trimIndent(),
            stationCode,
            issueDate,
        )
        return jdbc.queryForObject(
            """
            SELECT last_value
            FROM sales.ticket_daily_sequences
            WHERE station_code = ? AND issue_date = ?
            """.trimIndent(),
            Long::class.java,
            stationCode,
            issueDate,
        ) ?: 1L
    }

    private fun ResultSet.toTicket(): Ticket =
        Ticket(
            id = getObject("id", UUID::class.java),
            ticketNumber = getString("ticket_number"),
            reservationId = getObject("reservation_id", UUID::class.java),
            shiftId = getObject("shift_id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            passengerName = getString("passenger_name"),
            priceCents = getLong("price_cents"),
            status = TicketStatus.valueOf(getString("status")),
            issuedAt = getTimestamp("issued_at").toInstant(),
            orderId = getObject("order_id", UUID::class.java),
            docType = getString("doc_type")?.let(DocType::valueOf),
            docNumber = getString("doc_number"),
        )
}
