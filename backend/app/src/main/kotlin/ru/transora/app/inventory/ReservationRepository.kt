package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.Reservation
import ru.transora.inventory.domain.ReservationStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ReservationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(reservation: Reservation) {
        jdbc.update(
            """
            INSERT INTO inventory.reservations (
                id, trip_id, seat_number, status, expires_at, created_at,
                session_id, from_stop_order, to_stop_order, inventory_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            reservation.id,
            reservation.tripId,
            reservation.seatNumber,
            reservation.status.name,
            Timestamp.from(reservation.expiresAt),
            Timestamp.from(reservation.createdAt),
            reservation.sessionId,
            reservation.fromStopOrder,
            reservation.toStopOrder,
            reservation.inventoryId,
        )
    }

    fun findByIdForUpdate(id: UUID): Reservation? =
        jdbc.query(
            "SELECT * FROM inventory.reservations WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.toReservation() },
            id,
        ).firstOrNull()

    fun listActiveAboveSeat(tripId: UUID, maxSeatNumber: Int): List<Reservation> =
        jdbc.query(
            """
            SELECT *
            FROM inventory.reservations
            WHERE trip_id = ? AND seat_number > ? AND status = ?
            ORDER BY seat_number
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toReservation() },
            tripId,
            maxSeatNumber,
            ReservationStatus.ACTIVE.name,
        )

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

    fun listActiveByTripSeat(tripId: UUID, seatNumber: Int): List<Reservation> =
        jdbc.query(
            """
            SELECT *
            FROM inventory.reservations
            WHERE trip_id = ? AND seat_number = ? AND status = ?
            ORDER BY created_at
            """.trimIndent(),
            { rs, _ -> rs.toReservation() },
            tripId,
            seatNumber,
            ReservationStatus.ACTIVE.name,
        )

    fun countActiveByTripSeat(tripId: UUID, seatNumber: Int): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory.reservations
            WHERE trip_id = ? AND seat_number = ? AND status = ?
            """.trimIndent(),
            Int::class.java,
            tripId,
            seatNumber,
            ReservationStatus.ACTIVE.name,
        ) ?: 0

    fun countActiveBySession(sessionId: String): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory.reservations
            WHERE session_id = ? AND status = ?
            """.trimIndent(),
            Int::class.java,
            sessionId,
            ReservationStatus.ACTIVE.name,
        ) ?: 0

    fun findExpiredActive(now: Instant, limit: Int = 100): List<Reservation> =
        jdbc.query(
            """
            SELECT *
            FROM inventory.reservations
            WHERE status = ? AND expires_at <= ?
            ORDER BY expires_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            { rs, _ -> rs.toReservation() },
            ReservationStatus.ACTIVE.name,
            Timestamp.from(now),
            limit,
        )

    fun updateStatus(id: UUID, status: ReservationStatus) {
        jdbc.update(
            "UPDATE inventory.reservations SET status = ? WHERE id = ?",
            status.name,
            id,
        )
    }

    fun countActive(): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM inventory.reservations
            WHERE status = ?
            """.trimIndent(),
            Int::class.java,
            ReservationStatus.ACTIVE.name,
        ) ?: 0

    private fun ResultSet.toReservation(): Reservation =
        Reservation(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            seatNumber = getInt("seat_number"),
            status = ReservationStatus.valueOf(getString("status")),
            expiresAt = getTimestamp("expires_at").toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
            sessionId = getString("session_id"),
            fromStopOrder = getObject("from_stop_order")?.let { (it as Number).toInt() },
            toStopOrder = getObject("to_stop_order")?.let { (it as Number).toInt() },
            inventoryId = getObject("inventory_id", UUID::class.java),
        )
}
