package ru.transora.app.scheduling

import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.scheduling.domain.Schedule
import ru.transora.scheduling.domain.ScheduleEntry
import ru.transora.scheduling.domain.ScheduleType
import ru.transora.scheduling.domain.ScheduleWithEntries
import java.sql.Array
import java.sql.Date
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Repository
class ScheduleRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(schedule: Schedule) {
        jdbc.update(
            """
            INSERT INTO scheduling.schedules (
                id, route_id, name, schedule_type, valid_from, valid_to,
                is_active, created_by, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            schedule.id,
            schedule.routeId,
            schedule.name,
            schedule.scheduleType.name,
            schedule.validFrom?.let { Date.valueOf(it) },
            schedule.validTo?.let { Date.valueOf(it) },
            schedule.isActive,
            schedule.createdBy,
            Timestamp.from(schedule.createdAt),
            Timestamp.from(schedule.updatedAt),
        )
    }

    fun insertEntry(entry: ScheduleEntry) {
        jdbc.update(
            """
            INSERT INTO scheduling.schedule_entries (
                id, schedule_id, trip_number, departure_time, days_of_week,
                default_vehicle_id, is_active, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entry.id,
            entry.scheduleId,
            entry.tripNumber,
            Time.valueOf(entry.departureTime),
            entry.daysOfWeek.toSqlArray(),
            entry.defaultVehicleId,
            entry.isActive,
            Timestamp.from(entry.createdAt),
        )
    }

    fun findById(id: UUID): Schedule? =
        jdbc.query(
            "SELECT * FROM scheduling.schedules WHERE id = ?",
            { rs, _ -> rs.toSchedule() },
            id,
        ).firstOrNull()

    fun findWithEntries(id: UUID): ScheduleWithEntries? {
        val schedule = findById(id) ?: return null
        return ScheduleWithEntries(schedule, listEntries(id))
    }

    fun list(): List<Schedule> =
        jdbc.query(
            "SELECT * FROM scheduling.schedules ORDER BY name",
        ) { rs, _ -> rs.toSchedule() }

    fun listEntries(scheduleId: UUID): List<ScheduleEntry> =
        jdbc.query(
            """
            SELECT * FROM scheduling.schedule_entries
            WHERE schedule_id = ?
            ORDER BY departure_time
            """.trimIndent(),
            { rs, _ -> rs.toScheduleEntry() },
            scheduleId,
        )

    fun listActiveWithEntries(): List<ScheduleWithEntries> =
        list().filter { it.isActive }.mapNotNull { findWithEntries(it.id) }

    fun update(schedule: Schedule): Int =
        jdbc.update(
            """
            UPDATE scheduling.schedules
            SET name = ?, schedule_type = ?, valid_from = ?, valid_to = ?,
                is_active = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            schedule.name,
            schedule.scheduleType.name,
            schedule.validFrom?.let { Date.valueOf(it) },
            schedule.validTo?.let { Date.valueOf(it) },
            schedule.isActive,
            Timestamp.from(schedule.updatedAt),
            schedule.id,
        )

    fun deleteEntries(scheduleId: UUID) {
        jdbc.update("DELETE FROM scheduling.schedule_entries WHERE schedule_id = ?", scheduleId)
    }

    private fun List<Int>.toSqlArray(): java.sql.Array =
        jdbc.execute(
            ConnectionCallback<java.sql.Array> { connection ->
                connection.createArrayOf("smallint", map { it.toShort() }.toTypedArray())
            },
        )!!

    private fun ResultSet.readDaysOfWeek(): List<Int> {
        val sqlArray = getArray("days_of_week") ?: return emptyList()
        val raw = sqlArray.array as kotlin.Array<*>
        return raw.map { (it as Number).toInt() }
    }

    private fun ResultSet.toSchedule(): Schedule =
        Schedule(
            id = getObject("id", UUID::class.java),
            routeId = getObject("route_id", UUID::class.java),
            name = getString("name"),
            scheduleType = ScheduleType.valueOf(getString("schedule_type")),
            validFrom = getDate("valid_from")?.toLocalDate(),
            validTo = getDate("valid_to")?.toLocalDate(),
            isActive = getBoolean("is_active"),
            createdBy = getObject("created_by", UUID::class.java),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.toScheduleEntry(): ScheduleEntry =
        ScheduleEntry(
            id = getObject("id", UUID::class.java),
            scheduleId = getObject("schedule_id", UUID::class.java),
            tripNumber = getString("trip_number"),
            departureTime = getTime("departure_time").toLocalTime(),
            daysOfWeek = readDaysOfWeek(),
            defaultVehicleId = getObject("default_vehicle_id") as UUID?,
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}
