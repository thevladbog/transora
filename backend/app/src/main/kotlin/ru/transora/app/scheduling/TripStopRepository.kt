package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.TripStop
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class TripStopRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(stop: TripStop) {
        jdbc.update(
            """
            INSERT INTO scheduling.trip_stops (
                id, trip_id, route_stop_id, stop_order, stop_name, station_id, is_external,
                scheduled_arrival, scheduled_departure, estimated_arrival, estimated_departure,
                actual_arrival, actual_departure, stop_status, updated_by, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            stop.id,
            stop.tripId,
            stop.routeStopId,
            stop.stopOrder,
            stop.stopName,
            stop.stationId,
            stop.isExternal,
            stop.scheduledArrival?.let { Timestamp.from(it) },
            Timestamp.from(stop.scheduledDeparture),
            stop.estimatedArrival?.let { Timestamp.from(it) },
            stop.estimatedDeparture?.let { Timestamp.from(it) },
            stop.actualArrival?.let { Timestamp.from(it) },
            stop.actualDeparture?.let { Timestamp.from(it) },
            stop.stopStatus.name,
            stop.updatedBy,
            Timestamp.from(stop.updatedAt),
        )
    }

    fun listByTripId(tripId: UUID): List<TripStop> =
        jdbc.query(
            """
            SELECT * FROM scheduling.trip_stops
            WHERE trip_id = ?
            ORDER BY stop_order
            """.trimIndent(),
            { rs, _ -> rs.toTripStop() },
            tripId,
        )

    fun findByIdForUpdate(tripId: UUID, stopId: UUID): TripStop? =
        jdbc.query(
            """
            SELECT * FROM scheduling.trip_stops
            WHERE trip_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toTripStop() },
            tripId,
            stopId,
        ).firstOrNull()

    fun update(
        stopId: UUID,
        estimatedArrival: Instant?,
        estimatedDeparture: Instant?,
        actualArrival: Instant?,
        actualDeparture: Instant?,
        stopStatus: StopStatus,
        updatedBy: UUID?,
    ) {
        jdbc.update(
            """
            UPDATE scheduling.trip_stops
            SET estimated_arrival = ?,
                estimated_departure = ?,
                actual_arrival = ?,
                actual_departure = ?,
                stop_status = ?,
                updated_by = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            estimatedArrival?.let { Timestamp.from(it) },
            estimatedDeparture?.let { Timestamp.from(it) },
            actualArrival?.let { Timestamp.from(it) },
            actualDeparture?.let { Timestamp.from(it) },
            stopStatus.name,
            updatedBy,
            Timestamp.from(Instant.now()),
            stopId,
        )
    }

    fun updateEstimatedTimes(
        stopId: UUID,
        estimatedArrival: Instant?,
        estimatedDeparture: Instant?,
    ) {
        jdbc.update(
            """
            UPDATE scheduling.trip_stops
            SET estimated_arrival = ?,
                estimated_departure = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            estimatedArrival?.let { Timestamp.from(it) },
            estimatedDeparture?.let { Timestamp.from(it) },
            Timestamp.from(Instant.now()),
            stopId,
        )
    }

    private fun ResultSet.toTripStop(): TripStop =
        TripStop(
            id = getObject("id", UUID::class.java),
            tripId = getObject("trip_id", UUID::class.java),
            routeStopId = getObject("route_stop_id", UUID::class.java),
            stopOrder = getInt("stop_order"),
            stopName = getString("stop_name"),
            stationId = getObject("station_id") as UUID?,
            isExternal = getBoolean("is_external"),
            scheduledArrival = getTimestamp("scheduled_arrival")?.toInstant(),
            scheduledDeparture = getTimestamp("scheduled_departure").toInstant(),
            estimatedArrival = getTimestamp("estimated_arrival")?.toInstant(),
            estimatedDeparture = getTimestamp("estimated_departure")?.toInstant(),
            actualArrival = getTimestamp("actual_arrival")?.toInstant(),
            actualDeparture = getTimestamp("actual_departure")?.toInstant(),
            stopStatus = StopStatus.valueOf(getString("stop_status")),
            updatedBy = runCatching { getObject("updated_by") as UUID? }.getOrNull(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
}
