package ru.transora.app.notifications

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class AnnouncementTemplateRecord(
    val id: UUID,
    val code: String,
    val name: String,
    val templateText: String,
    val priority: String,
    val isActive: Boolean,
)

@Repository
class AnnouncementTemplateRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findByCode(code: String): AnnouncementTemplateRecord? =
        jdbc.query(
            """
            SELECT id, code, name, template_text, priority, is_active
            FROM notifications.announcement_templates
            WHERE code = ? AND is_active = TRUE
            """.trimIndent(),
            { rs, _ ->
                AnnouncementTemplateRecord(
                    id = rs.getObject("id", UUID::class.java),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    templateText = rs.getString("template_text"),
                    priority = rs.getString("priority"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
            code,
        ).firstOrNull()

    fun listActive(): List<AnnouncementTemplateRecord> =
        jdbc.query(
            """
            SELECT id, code, name, template_text, priority, is_active
            FROM notifications.announcement_templates
            WHERE is_active = TRUE
            ORDER BY code
            """.trimIndent(),
            { rs, _ ->
                AnnouncementTemplateRecord(
                    id = rs.getObject("id", UUID::class.java),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    templateText = rs.getString("template_text"),
                    priority = rs.getString("priority"),
                    isActive = rs.getBoolean("is_active"),
                )
            },
        )
}
