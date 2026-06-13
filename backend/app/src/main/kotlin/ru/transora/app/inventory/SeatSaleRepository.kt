package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class SeatSaleRecord(
    val id: UUID,
    val tripId: UUID,
    val seatNumber: Int,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val ticketId: UUID?,
    val reservationId: UUID?,
    val status: String,
    val createdAt: Instant,
)

@Repository
class SeatSaleRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(
        id: UUID,
        tripId: UUID,
        seatNumber: Int,
        fromStopOrder: Int,
        toStopOrder: Int,
        ticketId: UUID?,
        reservationId: UUID?,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory.seat_sales (
                id, trip_id, seat_number, from_stop_order, to_stop_order,
                ticket_id, reservation_id, status, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """.trimIndent(),
            id,
            tripId,
            seatNumber,
            fromStopOrder,
            toStopOrder,
            ticketId,
            reservationId,
            Timestamp.from(Instant.now()),
        )
    }

    fun findByTicketId(ticketId: UUID): SeatSaleRecord? =
        jdbc.query(
            "SELECT * FROM inventory.seat_sales WHERE ticket_id = ?",
            { rs, _ -> rs.toSeatSale() },
            ticketId,
        ).firstOrNull()

    fun markRefunded(id: UUID) {
        jdbc.update(
            "UPDATE inventory.seat_sales SET status = 'REFUNDED' WHERE id = ?",
            id,
        )
    }

    fun listActiveByTripSeat(tripId: UUID, seatNumber: Int): List<SeatSaleRecord> =
        jdbc.query(
            """
            SELECT * FROM inventory.seat_sales
            WHERE trip_id = ? AND seat_number = ? AND status = 'ACTIVE'
            """.trimIndent(),
            { rs, _ -> rs.toSeatSale() },
            tripId,
            seatNumber,
        )

    fun listActiveByTrip(tripId: UUID): List<SeatSaleRecord> =
        jdbc.query(
            """
            SELECT * FROM inventory.seat_sales
            WHERE trip_id = ? AND status = 'ACTIVE'
            ORDER BY seat_number, from_stop_order
            """.trimIndent(),
            { rs, _ -> rs.toSeatSale() },
            tripId,
        )

    fun countActiveByTripSeat(tripId: UUID, seatNumber: Int): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory.seat_sales
            WHERE trip_id = ? AND seat_number = ? AND status = 'ACTIVE'
            """.trimIndent(),
            Int::class.java,
            tripId,
            seatNumber,
        ) ?: 0

    private fun ResultSet.toSeatSale(): SeatSaleRecord =
        SeatSaleRecord(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            fromStopOrder = getInt("from_stop_order"),
            toStopOrder = getInt("to_stop_order"),
            ticketId = getObject("ticket_id", UUID::class.java),
            reservationId = getObject("reservation_id", UUID::class.java),
            status = getString("status"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
