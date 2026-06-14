package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Route
import ru.transora.scheduling.domain.RouteStop
import ru.transora.scheduling.domain.RouteWithStops
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class RouteRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(route: Route) {
        jdbc.update(
            """
            INSERT INTO scheduling.routes (
                id, carrier_id, name, code, route_number, description, is_active, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            route.id,
            route.carrierId,
            route.name,
            route.code,
            route.routeNumber,
            route.description,
            route.isActive,
            Timestamp.from(route.createdAt),
            Timestamp.from(route.updatedAt),
        )
    }

    fun insertStop(stop: RouteStop) {
        jdbc.update(
            """
            INSERT INTO scheduling.route_stops (
                id, route_id, stop_order, stop_name, station_id, point_id, is_external,
                scheduled_duration_min, dwell_time_min
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            stop.id,
            stop.routeId,
            stop.stopOrder,
            stop.stopName,
            stop.stationId,
            stop.pointId,
            stop.isExternal,
            stop.scheduledDurationMin,
            stop.dwellTimeMin,
        )
    }

    fun findById(id: UUID): Route? =
        jdbc.query(
            "SELECT * FROM scheduling.routes WHERE id = ?",
            { rs, _ -> rs.toRoute() },
            id,
        ).firstOrNull()

    fun findWithStops(id: UUID): RouteWithStops? {
        val route = findById(id) ?: return null
        return RouteWithStops(route, listStops(id))
    }

    fun list(): List<Route> =
        jdbc.query(
            "SELECT * FROM scheduling.routes ORDER BY name",
        ) { rs, _ -> rs.toRoute() }

    fun listStops(routeId: UUID): List<RouteStop> =
        jdbc.query(
            """
            SELECT * FROM scheduling.route_stops
            WHERE route_id = ?
            ORDER BY stop_order
            """.trimIndent(),
            { rs, _ -> rs.toRouteStop() },
            routeId,
        )

    fun update(route: Route): Int =
        jdbc.update(
            """
            UPDATE scheduling.routes
            SET name = ?, code = ?, route_number = ?, description = ?, is_active = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            route.name,
            route.code,
            route.routeNumber,
            route.description,
            route.isActive,
            Timestamp.from(route.updatedAt),
            route.id,
        )

    fun countActiveSchedules(routeId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM scheduling.schedules
            WHERE route_id = ? AND is_active = TRUE
            """.trimIndent(),
            Int::class.java,
            routeId,
        ) ?: 0

    fun countFutureTrips(routeId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM scheduling.trips
            WHERE route_id = ? AND trip_date >= CURRENT_DATE
              AND status NOT IN ('CANCELLED', 'COMPLETED')
            """.trimIndent(),
            Int::class.java,
            routeId,
        ) ?: 0

    fun deleteStops(routeId: UUID) {
        jdbc.update("DELETE FROM scheduling.route_stops WHERE route_id = ?", routeId)
    }

    private fun ResultSet.toRoute(): Route =
        Route(
            id = getObject("id", UUID::class.java),
            carrierId = getObject("carrier_id", UUID::class.java),
            name = getString("name"),
            code = getString("code"),
            routeNumber = getString("route_number"),
            description = getString("description"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.toRouteStop(): RouteStop =
        RouteStop(
            id = getObject("id", UUID::class.java),
            routeId = getObject("route_id", UUID::class.java),
            stopOrder = getInt("stop_order"),
            stopName = getString("stop_name"),
            stationId = getObject("station_id") as UUID?,
            pointId = getObject("point_id") as UUID?,
            isExternal = getBoolean("is_external"),
            scheduledDurationMin = getObject("scheduled_duration_min") as Int?,
            dwellTimeMin = getInt("dwell_time_min"),
        )
}
