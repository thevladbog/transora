package ru.transora.app.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class PointAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun list(): List<PointRow> =
        jdbc.query(
            "SELECT * FROM scheduling.points ORDER BY name",
            { rs, _ -> rs.toRow() },
        )

    fun findById(id: UUID): PointRow? =
        jdbc.query("SELECT * FROM scheduling.points WHERE id = ?", { rs, _ -> rs.toRow() }, id).firstOrNull()

    fun insert(row: PointRow) {
        jdbc.update(
            """
            INSERT INTO scheduling.points (
                id, code, name, city, address, latitude, longitude, timezone, is_active, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.code,
            row.name,
            row.city,
            row.address,
            row.latitude,
            row.longitude,
            row.timezone,
            row.isActive,
            Timestamp.from(row.createdAt),
        )
    }

    fun update(row: PointRow) {
        jdbc.update(
            """
            UPDATE scheduling.points
            SET code = ?, name = ?, city = ?, address = ?, latitude = ?, longitude = ?,
                timezone = ?, is_active = ?
            WHERE id = ?
            """.trimIndent(),
            row.code,
            row.name,
            row.city,
            row.address,
            row.latitude,
            row.longitude,
            row.timezone,
            row.isActive,
            row.id,
        )
    }

    fun delete(id: UUID): Int = jdbc.update("DELETE FROM scheduling.points WHERE id = ?", id)

    fun existsByCode(code: String, excludeId: UUID? = null): Boolean {
        val rows = if (excludeId == null) {
            jdbc.queryForList(
                "SELECT 1 FROM scheduling.points WHERE code = ? LIMIT 1",
                Int::class.java,
                code,
            )
        } else {
            jdbc.queryForList(
                "SELECT 1 FROM scheduling.points WHERE code = ? AND id <> ? LIMIT 1",
                Int::class.java,
                code,
                excludeId,
            )
        }
        return rows.isNotEmpty()
    }

    private fun ResultSet.toRow(): PointRow =
        PointRow(
            id = getObject("id", UUID::class.java),
            code = getString("code"),
            name = getString("name"),
            city = getString("city"),
            address = getString("address"),
            latitude = getDouble("latitude"),
            longitude = getDouble("longitude"),
            timezone = getString("timezone"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class PointRow(
    val id: UUID,
    val code: String,
    val name: String,
    val city: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val isActive: Boolean,
    val createdAt: Instant,
)
