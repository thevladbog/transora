package ru.transora.app.dispatcher

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class DispatcherRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insertSalesRestriction(
        id: UUID,
        tripId: UUID?,
        scheduleEntryId: UUID?,
        stationId: UUID,
        allowedSeats: IntArray,
        scope: String,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory.sales_restrictions (
                id, trip_id, schedule_entry_id, station_id, allowed_seats, status, scope, created_at
            )
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            id,
            tripId,
            scheduleEntryId,
            stationId,
            allowedSeats,
            scope,
            Timestamp.from(Instant.now()),
        )
    }

    fun insertSeatBlock(
        id: UUID,
        tripId: UUID,
        seatNumber: Int,
        blockType: String,
        reason: String?,
        blockedBy: UUID,
        stationId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory.seat_blocks (
                id, trip_id, seat_number, block_type, reason,
                blocked_by, station_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            tripId,
            seatNumber,
            blockType,
            reason,
            blockedBy,
            stationId,
            Timestamp.from(Instant.now()),
        )
    }

    fun findSalesRestriction(id: UUID): SalesRestrictionRow? =
        jdbc.query(
            "SELECT * FROM inventory.sales_restrictions WHERE id = ?",
            { rs, _ -> rs.toSalesRestriction() },
            id,
        ).firstOrNull()

    fun findActiveSeatBlock(id: UUID): SeatBlockRow? =
        jdbc.query(
            """
            SELECT * FROM inventory.seat_blocks
            WHERE id = ? AND released_at IS NULL
            """.trimIndent(),
            { rs, _ -> rs.toSeatBlock() },
            id,
        ).firstOrNull()

    fun findSeatBlock(id: UUID): SeatBlockRow? =
        jdbc.query(
            "SELECT * FROM inventory.seat_blocks WHERE id = ?",
            { rs, _ -> rs.toSeatBlock() },
            id,
        ).firstOrNull()

    fun releaseSeatBlock(id: UUID, releasedBy: UUID, releasedAt: Instant): Int =
        jdbc.update(
            """
            UPDATE inventory.seat_blocks
            SET released_at = ?, released_by = ?
            WHERE id = ? AND released_at IS NULL
            """.trimIndent(),
            Timestamp.from(releasedAt),
            releasedBy,
            id,
        )

    fun updateSalesRestrictionStatus(id: UUID, status: String): Int =
        jdbc.update(
            "UPDATE inventory.sales_restrictions SET status = ? WHERE id = ?",
            status,
            id,
        )

    private fun ResultSet.toSalesRestriction(): SalesRestrictionRow =
        SalesRestrictionRow(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            scheduleEntryId = getObject("schedule_entry_id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            allowedSeats = (getArray("allowed_seats").array as Array<*>).map { (it as Number).toInt() },
            status = getString("status"),
            scope = getString("scope"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.toSeatBlock(): SeatBlockRow =
        SeatBlockRow(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            blockType = getString("block_type"),
            reason = getString("reason"),
            blockedBy = getObject("blocked_by", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            releasedAt = getTimestamp("released_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class SalesRestrictionRow(
    val id: UUID,
    val tripId: UUID?,
    val scheduleEntryId: UUID?,
    val stationId: UUID?,
    val allowedSeats: List<Int>,
    val status: String,
    val scope: String,
    val createdAt: Instant,
)

data class SeatBlockRow(
    val id: UUID,
    val tripId: UUID,
    val seatNumber: Int,
    val blockType: String,
    val reason: String?,
    val blockedBy: UUID?,
    val stationId: UUID?,
    val releasedAt: Instant?,
    val createdAt: Instant,
)
