package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.ServiceStation
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class ServiceStationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(station: ServiceStation) {
        jdbc.update(
            """
            INSERT INTO scheduling.service_stations (
                id, code, name, city, timezone, address, point_id, description, contact_phone,
                is_active, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            station.id,
            station.code,
            station.name,
            station.city,
            station.timezone,
            station.address,
            station.pointId,
            station.description,
            station.contactPhone,
            station.isActive,
            Timestamp.from(station.createdAt),
        )
    }

    fun findById(id: UUID): ServiceStation? =
        jdbc.query(
            "SELECT * FROM scheduling.service_stations WHERE id = ?",
            { rs, _ -> rs.toServiceStation() },
            id,
        ).firstOrNull()

    fun findByCode(code: String): ServiceStation? =
        jdbc.query(
            "SELECT * FROM scheduling.service_stations WHERE code = ?",
            { rs, _ -> rs.toServiceStation() },
            code,
        ).firstOrNull()

    fun findByPointId(pointId: UUID): ServiceStation? =
        jdbc.query(
            "SELECT * FROM scheduling.service_stations WHERE point_id = ? LIMIT 1",
            { rs, _ -> rs.toServiceStation() },
            pointId,
        ).firstOrNull()

    fun list(): List<ServiceStation> =
        jdbc.query(
            "SELECT * FROM scheduling.service_stations ORDER BY code",
        ) { rs, _ -> rs.toServiceStation() }

    fun update(station: ServiceStation): Int =
        jdbc.update(
            """
            UPDATE scheduling.service_stations
            SET name = ?, city = ?, timezone = ?, address = ?, point_id = ?,
                description = ?, contact_phone = ?, is_active = ?
            WHERE id = ?
            """.trimIndent(),
            station.name,
            station.city,
            station.timezone,
            station.address,
            station.pointId,
            station.description,
            station.contactPhone,
            station.isActive,
            station.id,
        )

    private fun ResultSet.toServiceStation(): ServiceStation =
        ServiceStation(
            id = getObject("id", UUID::class.java),
            code = getString("code"),
            name = getString("name"),
            city = getString("city"),
            timezone = getString("timezone"),
            address = getString("address"),
            pointId = getObject("point_id", UUID::class.java),
            description = getString("description"),
            contactPhone = getString("contact_phone"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
