package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Vehicle
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class VehicleRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(vehicle: Vehicle) {
        jdbc.update(
            """
            INSERT INTO scheduling.vehicles (
                id, carrier_id, model, plate_number, seat_layout_id, total_seats,
                year, notes, is_active, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            vehicle.id,
            vehicle.carrierId,
            vehicle.model,
            vehicle.plateNumber,
            vehicle.seatLayoutId,
            vehicle.totalSeats,
            vehicle.year,
            vehicle.notes,
            vehicle.isActive,
            Timestamp.from(vehicle.createdAt),
            Timestamp.from(vehicle.updatedAt),
        )
    }

    fun findById(id: UUID): Vehicle? =
        jdbc.query(
            "SELECT * FROM scheduling.vehicles WHERE id = ?",
            { rs, _ -> rs.toVehicle() },
            id,
        ).firstOrNull()

    fun list(): List<Vehicle> =
        jdbc.query(
            "SELECT * FROM scheduling.vehicles ORDER BY plate_number",
        ) { rs, _ -> rs.toVehicle() }

    fun update(vehicle: Vehicle): Int =
        jdbc.update(
            """
            UPDATE scheduling.vehicles
            SET model = ?, plate_number = ?, seat_layout_id = ?, total_seats = ?,
                year = ?, notes = ?, is_active = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            vehicle.model,
            vehicle.plateNumber,
            vehicle.seatLayoutId,
            vehicle.totalSeats,
            vehicle.year,
            vehicle.notes,
            vehicle.isActive,
            Timestamp.from(vehicle.updatedAt),
            vehicle.id,
        )

    private fun ResultSet.toVehicle(): Vehicle =
        Vehicle(
            id = getObject("id", UUID::class.java),
            carrierId = getObject("carrier_id", UUID::class.java),
            model = getString("model"),
            plateNumber = getString("plate_number"),
            seatLayoutId = getObject("seat_layout_id", UUID::class.java),
            totalSeats = getInt("total_seats"),
            year = getObject("year") as Int?,
            notes = getString("notes"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
}
