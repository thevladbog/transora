package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Trip
import ru.transora.scheduling.domain.TripStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class TripRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(trip: Trip) {
        jdbc.update(
            """
            INSERT INTO scheduling.trips (
                id, route_number, departure_station, arrival_station,
                departure_time, platform, status, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            trip.id,
            trip.routeNumber,
            trip.departureStation,
            trip.arrivalStation,
            Timestamp.from(trip.departureTime),
            trip.platform,
            trip.status.name,
            Timestamp.from(trip.createdAt),
        )
    }

    fun findById(id: UUID): Trip? =
        jdbc.query(
            "SELECT * FROM scheduling.trips WHERE id = ?",
            { rs, _ -> rs.toTrip() },
            id,
        ).firstOrNull()

    fun list(): List<Trip> =
        jdbc.query(
            "SELECT * FROM scheduling.trips ORDER BY departure_time",
        ) { rs, _ -> rs.toTrip() }

    private fun ResultSet.toTrip(): Trip =
        Trip(
            id = getObject("id", UUID::class.java),
            routeNumber = getString("route_number"),
            departureStation = getString("departure_station"),
            arrivalStation = getString("arrival_station"),
            departureTime = getTimestamp("departure_time").toInstant(),
            platform = getString("platform"),
            status = TripStatus.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

