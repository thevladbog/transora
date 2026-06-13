package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.SeatStatus
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

@Repository
class SeatRepository(
    private val jdbc: JdbcTemplate,
) {
    fun createSeats(tripId: UUID, seatCount: Int) {
        jdbc.batchUpdate(
            "INSERT INTO inventory.seats (trip_id, seat_number, status) VALUES (?, ?, ?)",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    ps.setObject(1, tripId)
                    ps.setInt(2, i + 1)
                    ps.setString(3, SeatStatus.AVAILABLE.name)
                }

                override fun getBatchSize(): Int = seatCount
            },
        )
    }

    fun findStatusForUpdate(tripId: UUID, seatNumber: Int): SeatStatus? =
        jdbc.query(
            """
            SELECT status
            FROM inventory.seats
            WHERE trip_id = ? AND seat_number = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> SeatStatus.valueOf(rs.getString("status")) },
            tripId,
            seatNumber,
        ).firstOrNull()

    fun updateStatus(tripId: UUID, seatNumber: Int, status: SeatStatus) {
        jdbc.update(
            "UPDATE inventory.seats SET status = ? WHERE trip_id = ? AND seat_number = ?",
            status.name,
            tripId,
            seatNumber,
        )
    }

    fun listByTrip(tripId: UUID): List<SeatAvailability> =
        jdbc.query(
            """
            SELECT seat_number, status
            FROM inventory.seats
            WHERE trip_id = ?
            ORDER BY seat_number
            """.trimIndent(),
            { rs, _ -> rs.toSeatAvailability(tripId) },
            tripId,
        )

    private fun ResultSet.toSeatAvailability(tripId: UUID): SeatAvailability =
        SeatAvailability(
            tripId = tripId,
            seatNumber = getInt("seat_number"),
            status = SeatStatus.valueOf(getString("status")),
        )
}

data class SeatAvailability(
    val tripId: UUID,
    val seatNumber: Int,
    val status: SeatStatus,
)
