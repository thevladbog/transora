package ru.transora.app.inventory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

data class SalesRestrictionRecord(
    val id: UUID,
    val tripId: UUID?,
    val scheduleEntryId: UUID?,
    val stationId: UUID?,
    val allowedSeats: List<Int>,
    val status: String,
)

@Repository
class SalesRestrictionRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findActiveForTripAndStation(tripId: UUID, stationId: UUID): SalesRestrictionRecord? {
        val tripSpecific = findActiveTripRestriction(tripId, stationId)
        if (tripSpecific != null) {
            return tripSpecific
        }

        val scheduleEntryId = jdbc.queryForObject(
            "SELECT schedule_entry_id FROM scheduling.trips WHERE id = ?",
            UUID::class.java,
            tripId,
        ) ?: return null

        return findActiveScheduleEntryRestriction(scheduleEntryId, stationId)
    }

    private fun findActiveTripRestriction(tripId: UUID, stationId: UUID): SalesRestrictionRecord? =
        jdbc.query(
            """
            SELECT * FROM inventory.sales_restrictions
            WHERE trip_id = ? AND station_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toRecord() },
            tripId,
            stationId,
        ).firstOrNull()

    private fun findActiveScheduleEntryRestriction(
        scheduleEntryId: UUID,
        stationId: UUID,
    ): SalesRestrictionRecord? =
        jdbc.query(
            """
            SELECT * FROM inventory.sales_restrictions
            WHERE schedule_entry_id = ? AND station_id = ? AND status = 'ACTIVE'
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toRecord() },
            scheduleEntryId,
            stationId,
        ).firstOrNull()

    private fun ResultSet.toRecord(): SalesRestrictionRecord =
        SalesRestrictionRecord(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            scheduleEntryId = getObject("schedule_entry_id", UUID::class.java),
            stationId = getObject("station_id", UUID::class.java),
            allowedSeats = (getArray("allowed_seats").array as Array<*>).map { (it as Number).toInt() },
            status = getString("status"),
        )
}
