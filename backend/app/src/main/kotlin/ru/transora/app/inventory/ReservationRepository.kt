package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.Reservation
import ru.transora.inventory.domain.ReservationStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class ReservationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(reservation: Reservation) {
        jdbc.update(
            """
            INSERT INTO inventory.reservations (
                id, trip_id, seat_number, status, expires_at, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reservation.id,
            reservation.tripId,
            reservation.seatNumber,
            reservation.status.name,
            Timestamp.from(reservation.expiresAt),
            Timestamp.from(reservation.createdAt),
        )
    }

    fun findByIdForUpdate(id: UUID): Reservation? =
        jdbc.query(
            "SELECT * FROM inventory.reservations WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.toReservation() },
            id,
        ).firstOrNull()

    fun findActiveBySeatForUpdate(tripId: UUID, seatNumber: Int): Reservation? =
        jdbc.query(
            """
            SELECT *
            FROM inventory.reservations
            WHERE trip_id = ? AND seat_number = ? AND status = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toReservation() },
            tripId,
            seatNumber,
            ReservationStatus.ACTIVE.name,
        ).firstOrNull()

    fun updateStatus(id: UUID, status: ReservationStatus) {
        jdbc.update(
            "UPDATE inventory.reservations SET status = ? WHERE id = ?",
            status.name,
            id,
        )
    }

    private fun ResultSet.toReservation(): Reservation =
        Reservation(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            status = ReservationStatus.valueOf(getString("status")),
            expiresAt = getTimestamp("expires_at").toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
