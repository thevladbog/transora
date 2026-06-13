package ru.transora.app.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.TripStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class BoardTripQueryRow(
    val tripId: UUID,
    val routeNumber: String,
    val tripNumber: String?,
    val departureStation: String,
    val arrivalStation: String,
    val departureStationCode: String,
    val departureTime: Instant,
    val expectedDepartureTime: Instant,
    val delayMinutes: Int?,
    val platform: String?,
    val tripStatus: TripStatus,
    val stopId: UUID,
    val stopOrder: Int,
    val stopName: String,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant,
    val estimatedArrival: Instant?,
    val estimatedDeparture: Instant?,
    val actualArrival: Instant?,
    val actualDeparture: Instant?,
    val stopStatus: StopStatus,
    val firstStopOrder: Int,
    val lastStopOrder: Int,
    val firstStopName: String,
    val lastStopName: String,
)

@Repository
class BoardTripQueryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listStopAwareRows(stationCode: String): List<BoardTripQueryRow> =
        jdbc.query(
            """
            SELECT
                t.id AS trip_id,
                t.route_number,
                t.trip_number,
                t.departure_station,
                t.arrival_station,
                t.departure_station_code,
                t.departure_time,
                t.expected_departure_time,
                t.delay_minutes,
                t.platform,
                t.status AS trip_status,
                ts.id AS stop_id,
                ts.stop_order,
                ts.stop_name,
                ts.scheduled_arrival,
                ts.scheduled_departure,
                ts.estimated_arrival,
                ts.estimated_departure,
                ts.actual_arrival,
                ts.actual_departure,
                ts.stop_status,
                bounds.first_stop_order,
                bounds.last_stop_order,
                (SELECT s.stop_name FROM scheduling.trip_stops s
                 WHERE s.trip_id = t.id ORDER BY s.stop_order ASC LIMIT 1) AS first_stop_name,
                (SELECT s.stop_name FROM scheduling.trip_stops s
                 WHERE s.trip_id = t.id ORDER BY s.stop_order DESC LIMIT 1) AS last_stop_name
            FROM scheduling.trip_stops ts
            INNER JOIN scheduling.trips t ON t.id = ts.trip_id
            INNER JOIN scheduling.service_stations ss ON ss.id = ts.station_id
            INNER JOIN LATERAL (
                SELECT
                    MIN(s.stop_order) AS first_stop_order,
                    MAX(s.stop_order) AS last_stop_order
                FROM scheduling.trip_stops s
                WHERE s.trip_id = t.id
            ) bounds ON TRUE
            WHERE UPPER(ss.code) = UPPER(?)
            """.trimIndent(),
            { rs, _ -> rs.toBoardTripQueryRow() },
            stationCode.trim(),
        )

    fun listLegacyFlatRows(stationCode: String): List<BoardTripQueryRow> =
        jdbc.query(
            """
            SELECT
                t.id AS trip_id,
                t.route_number,
                t.trip_number,
                t.departure_station,
                t.arrival_station,
                t.departure_station_code,
                t.departure_time,
                t.expected_departure_time,
                t.delay_minutes,
                t.platform,
                t.status AS trip_status,
                t.id AS stop_id,
                1 AS stop_order,
                t.departure_station AS stop_name,
                NULL AS scheduled_arrival,
                t.departure_time AS scheduled_departure,
                NULL AS estimated_arrival,
                t.expected_departure_time AS estimated_departure,
                NULL AS actual_arrival,
                NULL AS actual_departure,
                'UPCOMING' AS stop_status,
                1 AS first_stop_order,
                1 AS last_stop_order,
                t.departure_station AS first_stop_name,
                t.arrival_station AS last_stop_name
            FROM scheduling.trips t
            WHERE UPPER(t.departure_station_code) = UPPER(?)
              AND NOT EXISTS (
                  SELECT 1 FROM scheduling.trip_stops ts WHERE ts.trip_id = t.id
              )
            """.trimIndent(),
            { rs, _ -> rs.toBoardTripQueryRow() },
            stationCode.trim(),
        )

    fun listStationCodesForTrip(tripId: UUID): List<String> =
        jdbc.queryForList(
            """
            SELECT DISTINCT ss.code
            FROM scheduling.trip_stops ts
            INNER JOIN scheduling.service_stations ss ON ss.id = ts.station_id
            WHERE ts.trip_id = ?
            ORDER BY ss.code
            """.trimIndent(),
            String::class.java,
            tripId,
        ).filterNotNull()

    private fun ResultSet.toBoardTripQueryRow(): BoardTripQueryRow =
        BoardTripQueryRow(
            tripId = getObject("trip_id", UUID::class.java),
            routeNumber = getString("route_number"),
            tripNumber = getString("trip_number"),
            departureStation = getString("departure_station"),
            arrivalStation = getString("arrival_station"),
            departureStationCode = getString("departure_station_code"),
            departureTime = getTimestamp("departure_time").toInstant(),
            expectedDepartureTime = getTimestamp("expected_departure_time").toInstant(),
            delayMinutes = getObject("delay_minutes") as Int?,
            platform = getString("platform"),
            tripStatus = TripStatus.valueOf(getString("trip_status")),
            stopId = getObject("stop_id", UUID::class.java),
            stopOrder = getInt("stop_order"),
            stopName = getString("stop_name"),
            scheduledArrival = getTimestamp("scheduled_arrival")?.toInstant(),
            scheduledDeparture = getTimestamp("scheduled_departure").toInstant(),
            estimatedArrival = getTimestamp("estimated_arrival")?.toInstant(),
            estimatedDeparture = getTimestamp("estimated_departure")?.toInstant(),
            actualArrival = getTimestamp("actual_arrival")?.toInstant(),
            actualDeparture = getTimestamp("actual_departure")?.toInstant(),
            stopStatus = StopStatus.valueOf(getString("stop_status")),
            firstStopOrder = getInt("first_stop_order"),
            lastStopOrder = getInt("last_stop_order"),
            firstStopName = getString("first_stop_name"),
            lastStopName = getString("last_stop_name"),
        )
}
