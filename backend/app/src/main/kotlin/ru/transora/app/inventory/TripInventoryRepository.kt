package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.TripInventory
import ru.transora.inventory.domain.TripInventoryStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class TripInventoryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(inventory: TripInventory) {
        jdbc.update(
            """
            INSERT INTO inventory.trip_inventory (id, trip_id, total_seats, status, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            inventory.id,
            inventory.tripId,
            inventory.totalSeats,
            inventory.status.name,
            Timestamp.from(inventory.createdAt),
        )
    }

    fun findByTripId(tripId: UUID): TripInventory? =
        jdbc.query(
            "SELECT * FROM inventory.trip_inventory WHERE trip_id = ?",
            { rs, _ -> rs.toTripInventory() },
            tripId,
        ).firstOrNull()

    fun existsByTripId(tripId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM inventory.trip_inventory WHERE trip_id = ?)",
            Boolean::class.java,
            tripId,
        ) ?: false

    fun updateStatus(tripId: UUID, status: TripInventoryStatus) {
        jdbc.update(
            "UPDATE inventory.trip_inventory SET status = ? WHERE trip_id = ?",
            status.name,
            tripId,
        )
    }

    fun updateTotalSeats(tripId: UUID, totalSeats: Int) {
        jdbc.update(
            "UPDATE inventory.trip_inventory SET total_seats = ? WHERE trip_id = ?",
            totalSeats,
            tripId,
        )
    }

    private fun ResultSet.toTripInventory(): TripInventory =
        TripInventory(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            totalSeats = getInt("total_seats"),
            status = TripInventoryStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
