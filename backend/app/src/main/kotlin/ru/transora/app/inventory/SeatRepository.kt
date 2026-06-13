package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.SeatStatus
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

data class ReaccommodationResult(
    val reaccommodationSeatNumbers: List<Int>,
)

data class SeatRecord(
    val seatNumber: Int,
    val status: SeatStatus,
    val requiresReaccommodation: Boolean,
)

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

    fun listByTripForUpdate(tripId: UUID): List<SeatRecord> =
        jdbc.query(
            """
            SELECT seat_number, status, requires_reaccommodation
            FROM inventory.seats
            WHERE trip_id = ?
            ORDER BY seat_number
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toSeatRecord() },
            tripId,
        )

    fun findReservedSeatNumbersAbove(tripId: UUID, maxSeatNumber: Int): List<Int> =
        jdbc.query(
            """
            SELECT seat_number
            FROM inventory.seats
            WHERE trip_id = ? AND seat_number > ? AND status = ?
            ORDER BY seat_number
            """.trimIndent(),
            { rs, _ -> rs.getInt("seat_number") },
            tripId,
            maxSeatNumber,
            SeatStatus.RESERVED.name,
        )

    fun reaccommodateSeats(
        tripId: UUID,
        newSeatCount: Int,
        soldSeatNumbers: Set<Int>,
    ): ReaccommodationResult {
        val existing = listByTripForUpdate(tripId)
        val existingByNumber = existing.associateBy { it.seatNumber }
        val reaccommodationSeats = mutableListOf<Int>()

        existing
            .filter { it.seatNumber <= newSeatCount && it.requiresReaccommodation }
            .forEach { clearRequiresReaccommodation(tripId, it.seatNumber) }

        existing
            .filter { it.seatNumber > newSeatCount }
            .forEach { seat ->
                when {
                    seat.seatNumber in soldSeatNumbers || seat.status == SeatStatus.SOLD -> {
                        markRequiresReaccommodation(tripId, seat.seatNumber)
                        reaccommodationSeats.add(seat.seatNumber)
                    }
                    seat.status == SeatStatus.RESERVED || seat.status == SeatStatus.AVAILABLE -> {
                        deleteSeat(tripId, seat.seatNumber)
                    }
                }
            }

        for (seatNumber in 1..newSeatCount) {
            if (seatNumber !in existingByNumber) {
                insertAvailable(tripId, seatNumber)
            }
        }

        return ReaccommodationResult(reaccommodationSeats.distinct().sorted())
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
            SELECT seat_number, status, requires_reaccommodation
            FROM inventory.seats
            WHERE trip_id = ?
            ORDER BY seat_number
            """.trimIndent(),
            { rs, _ -> rs.toSeatAvailability(tripId) },
            tripId,
        )

    private fun insertAvailable(tripId: UUID, seatNumber: Int) {
        jdbc.update(
            """
            INSERT INTO inventory.seats (trip_id, seat_number, status, requires_reaccommodation)
            VALUES (?, ?, ?, FALSE)
            """.trimIndent(),
            tripId,
            seatNumber,
            SeatStatus.AVAILABLE.name,
        )
    }

    private fun markRequiresReaccommodation(tripId: UUID, seatNumber: Int) {
        jdbc.update(
            """
            UPDATE inventory.seats
            SET requires_reaccommodation = TRUE
            WHERE trip_id = ? AND seat_number = ?
            """.trimIndent(),
            tripId,
            seatNumber,
        )
    }

    private fun clearRequiresReaccommodation(tripId: UUID, seatNumber: Int) {
        jdbc.update(
            """
            UPDATE inventory.seats
            SET requires_reaccommodation = FALSE
            WHERE trip_id = ? AND seat_number = ?
            """.trimIndent(),
            tripId,
            seatNumber,
        )
    }

    private fun deleteSeat(tripId: UUID, seatNumber: Int) {
        jdbc.update(
            "DELETE FROM inventory.seats WHERE trip_id = ? AND seat_number = ?",
            tripId,
            seatNumber,
        )
    }

    private fun ResultSet.toSeatRecord(): SeatRecord =
        SeatRecord(
            seatNumber = getInt("seat_number"),
            status = SeatStatus.valueOf(getString("status")),
            requiresReaccommodation = getBoolean("requires_reaccommodation"),
        )

    private fun ResultSet.toSeatAvailability(tripId: UUID): SeatAvailability =
        SeatAvailability(
            tripId = tripId,
            seatNumber = getInt("seat_number"),
            status = SeatStatus.valueOf(getString("status")),
            requiresReaccommodation = getBoolean("requires_reaccommodation"),
        )
}

data class SeatAvailability(
    val tripId: UUID,
    val seatNumber: Int,
    val status: SeatStatus,
    val requiresReaccommodation: Boolean = false,
)
