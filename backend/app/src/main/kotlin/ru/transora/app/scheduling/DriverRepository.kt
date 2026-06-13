package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Driver
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class DriverRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(driver: Driver) {
        jdbc.update(
            """
            INSERT INTO scheduling.drivers (id, carrier_id, full_name, license_no, phone, is_active, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            driver.id,
            driver.carrierId,
            driver.fullName,
            driver.licenseNo,
            driver.phone,
            driver.isActive,
            Timestamp.from(driver.createdAt),
        )
    }

    fun findById(id: UUID): Driver? =
        jdbc.query(
            "SELECT * FROM scheduling.drivers WHERE id = ?",
            { rs, _ -> rs.toDriver() },
            id,
        ).firstOrNull()

    fun list(): List<Driver> =
        jdbc.query("SELECT * FROM scheduling.drivers ORDER BY full_name") { rs, _ -> rs.toDriver() }

    fun update(driver: Driver): Int =
        jdbc.update(
            """
            UPDATE scheduling.drivers
            SET full_name = ?, license_no = ?, phone = ?, is_active = ?
            WHERE id = ?
            """.trimIndent(),
            driver.fullName,
            driver.licenseNo,
            driver.phone,
            driver.isActive,
            driver.id,
        )

    private fun ResultSet.toDriver(): Driver =
        Driver(
            id = getObject("id", UUID::class.java),
            carrierId = getObject("carrier_id", UUID::class.java),
            fullName = getString("full_name"),
            licenseNo = getString("license_no"),
            phone = getString("phone"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
