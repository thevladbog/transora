package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Ticket
import java.sql.Timestamp

@Repository
class TicketRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(ticket: Ticket) {
        jdbc.update(
            """
            INSERT INTO sales.tickets (
                id, reservation_id, shift_id, trip_id, seat_number,
                passenger_name, price_cents, status, issued_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ticket.id,
            ticket.reservationId,
            ticket.shiftId,
            ticket.tripId,
            ticket.seatNumber,
            ticket.passengerName,
            ticket.priceCents,
            ticket.status.name,
            Timestamp.from(ticket.issuedAt),
        )
    }
}
