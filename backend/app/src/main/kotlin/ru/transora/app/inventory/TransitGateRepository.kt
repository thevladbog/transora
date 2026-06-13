package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.inventory.domain.TransitGateStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class TransitGateRecord(
    val id: UUID,
    val tripId: UUID,
    val stationId: UUID,
    val stopOrder: Int,
    val status: TransitGateStatus,
    val availableSeats: List<Int>?,
    val openedBy: UUID?,
    val openedAt: Instant?,
    val closedBy: UUID?,
    val closedAt: Instant?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Repository
class TransitGateRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insertAwaitingGate(tripId: UUID, stationId: UUID, stopOrder: Int) {
        jdbc.update(
            """
            INSERT INTO inventory.transit_gates (
                id, trip_id, station_id, stop_order, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (trip_id, station_id) DO NOTHING
            """.trimIndent(),
            UUID.randomUUID(),
            tripId,
            stationId,
            stopOrder,
            TransitGateStatus.AWAITING_ARRIVAL.name,
        )
    }

    fun listByTripId(tripId: UUID): List<TransitGateRecord> =
        jdbc.query(
            """
            SELECT * FROM inventory.transit_gates
            WHERE trip_id = ?
            ORDER BY stop_order
            """.trimIndent(),
            { rs, _ -> rs.toTransitGate() },
            tripId,
        )

    fun listByTripAndStation(tripId: UUID, stationId: UUID): List<TransitGateRecord> =
        jdbc.query(
            """
            SELECT * FROM inventory.transit_gates
            WHERE trip_id = ? AND station_id = ?
            ORDER BY stop_order
            """.trimIndent(),
            { rs, _ -> rs.toTransitGate() },
            tripId,
            stationId,
        )

    fun findByTripAndStation(tripId: UUID, stationId: UUID): TransitGateRecord? =
        listByTripAndStation(tripId, stationId).firstOrNull()

    fun findById(id: UUID): TransitGateRecord? =
        jdbc.query(
            "SELECT * FROM inventory.transit_gates WHERE id = ?",
            { rs, _ -> rs.toTransitGate() },
            id,
        ).firstOrNull()

    fun findByIdForUpdate(id: UUID): TransitGateRecord? =
        jdbc.query(
            "SELECT * FROM inventory.transit_gates WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.toTransitGate() },
            id,
        ).firstOrNull()

    fun markOpen(
        id: UUID,
        availableSeats: IntArray,
        openedBy: UUID,
        notes: String?,
        openedAt: Instant,
    ) {
        jdbc.update(
            """
            UPDATE inventory.transit_gates
            SET status = ?, available_seats = ?, opened_by = ?, opened_at = ?,
                notes = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            TransitGateStatus.OPEN.name,
            availableSeats,
            openedBy,
            Timestamp.from(openedAt),
            notes,
            Timestamp.from(openedAt),
            id,
        )
    }

    fun markClosed(id: UUID, closedBy: UUID, notes: String?, closedAt: Instant) {
        jdbc.update(
            """
            UPDATE inventory.transit_gates
            SET status = ?, closed_by = ?, closed_at = ?,
                notes = COALESCE(?, notes), updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            TransitGateStatus.CLOSED.name,
            closedBy,
            Timestamp.from(closedAt),
            notes,
            Timestamp.from(closedAt),
            id,
        )
    }

    private fun ResultSet.toTransitGate(): TransitGateRecord =
        TransitGateRecord(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            stopOrder = getInt("stop_order"),
            status = TransitGateStatus.parse(getString("status")),
            availableSeats = readIntArray(getArray("available_seats")),
            openedBy = getObject("opened_by", UUID::class.java),
            openedAt = getTimestamp("opened_at")?.toInstant(),
            closedBy = getObject("closed_by", UUID::class.java),
            closedAt = getTimestamp("closed_at")?.toInstant(),
            notes = getString("notes"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at")?.toInstant() ?: getTimestamp("created_at").toInstant(),
        )

    private fun readIntArray(sqlArray: java.sql.Array?): List<Int>? {
        if (sqlArray == null) return null
        @Suppress("UNCHECKED_CAST")
        return (sqlArray.array as Array<*>).map { (it as Number).toInt() }
    }
}
