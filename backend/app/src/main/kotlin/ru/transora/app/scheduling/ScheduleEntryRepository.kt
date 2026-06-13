package ru.transora.app.scheduling

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ScheduleEntryRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findById(id: UUID): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM scheduling.schedule_entries WHERE id = ?",
            Int::class.java,
            id,
        ) ?: 0) > 0
}
