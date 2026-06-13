package ru.transora.app.documents

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

data class GeneratedDocumentRecord(
    val id: UUID,
    val ticketId: UUID,
    val contentType: String,
    val storagePath: String,
    val contentHash: String,
)

@Repository
class GeneratedDocumentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findByTicketId(ticketId: UUID): GeneratedDocumentRecord? =
        jdbc.query(
            """
            SELECT id, ticket_id, content_type, storage_path, content_hash
            FROM documents.generated_documents
            WHERE ticket_id = ?
            """.trimIndent(),
            { rs, _ ->
                GeneratedDocumentRecord(
                    id = rs.getObject("id", UUID::class.java),
                    ticketId = rs.getObject("ticket_id", UUID::class.java),
                    contentType = rs.getString("content_type"),
                    storagePath = rs.getString("storage_path"),
                    contentHash = rs.getString("content_hash"),
                )
            },
            ticketId,
        ).firstOrNull()

    fun deleteByTicketId(ticketId: UUID) {
        jdbc.update("DELETE FROM documents.generated_documents WHERE ticket_id = ?", ticketId)
    }

    fun insert(record: GeneratedDocumentRecord) {
        jdbc.update(
            """
            INSERT INTO documents.generated_documents (
                id, ticket_id, doc_type, content_type, storage_path, content_hash, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.ticketId,
            "TICKET",
            record.contentType,
            record.storagePath,
            record.contentHash,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }
}
