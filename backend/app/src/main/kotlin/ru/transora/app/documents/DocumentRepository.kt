package ru.transora.app.documents

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class DocumentType {
    TICKET,
    TRIP_MANIFEST,
    BOARDING_SHEET,
    CARRIER_REPORT,
}

enum class DocumentRequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CACHED,
}

data class StoredDocumentRecord(
    val id: UUID,
    val docType: DocumentType,
    val ticketId: UUID?,
    val tripId: UUID?,
    val requestId: UUID?,
    val contentType: String,
    val storagePath: String,
    val contentHash: String,
)

data class DocumentRequestRecord(
    val id: UUID,
    val docType: DocumentType,
    val status: DocumentRequestStatus,
    val paramsJson: String,
    val paramsHash: String,
    val resultDocumentId: UUID?,
)

data class TripDocumentSetRecord(
    val tripId: UUID,
    val manifestDocId: UUID?,
    val boardingSheetDocId: UUID?,
    val carrierReportDocId: UUID?,
    val manifestVersion: Int,
    val lastGeneratedAt: Instant?,
)

@Repository
class DocumentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findById(id: UUID): StoredDocumentRecord? =
        jdbc.query(
            """
            SELECT id, doc_type, ticket_id, trip_id, request_id, content_type, storage_path, content_hash
            FROM documents.generated_documents
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                StoredDocumentRecord(
                    id = rs.getObject("id", UUID::class.java),
                    docType = DocumentType.valueOf(rs.getString("doc_type")),
                    ticketId = rs.getObject("ticket_id", UUID::class.java),
                    tripId = rs.getObject("trip_id", UUID::class.java),
                    requestId = rs.getObject("request_id", UUID::class.java),
                    contentType = rs.getString("content_type"),
                    storagePath = rs.getString("storage_path"),
                    contentHash = rs.getString("content_hash"),
                )
            },
            id,
        ).firstOrNull()

    fun insertRequest(record: DocumentRequestRecord) {
        jdbc.update(
            """
            INSERT INTO documents.document_requests (
                id, doc_type, status, requested_by, params_json, params_hash,
                result_document_id, created_at, completed_at
            )
            VALUES (?, ?, ?, 'system', ?::jsonb, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.docType.name,
            record.status.name,
            record.paramsJson,
            record.paramsHash,
            record.resultDocumentId,
            Timestamp.from(Clock.systemUTC().instant()),
            if (record.status == DocumentRequestStatus.COMPLETED) Timestamp.from(Clock.systemUTC().instant()) else null,
        )
    }

    fun completeRequest(requestId: UUID, documentId: UUID, processingMs: Int) {
        jdbc.update(
            """
            UPDATE documents.document_requests
            SET status = ?, result_document_id = ?, processing_ms = ?, completed_at = ?
            WHERE id = ?
            """.trimIndent(),
            DocumentRequestStatus.COMPLETED.name,
            documentId,
            processingMs,
            Timestamp.from(Clock.systemUTC().instant()),
            requestId,
        )
    }

    fun insertStoredDocument(record: StoredDocumentRecord) {
        jdbc.update(
            """
            INSERT INTO documents.generated_documents (
                id, ticket_id, trip_id, request_id, doc_type, content_type, storage_path, content_hash, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.ticketId,
            record.tripId,
            record.requestId,
            record.docType.name,
            record.contentType,
            record.storagePath,
            record.contentHash,
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }

    fun findCompletedRequest(docType: DocumentType, paramsHash: String): DocumentRequestRecord? =
        jdbc.query(
            """
            SELECT id, doc_type, status, params_json::text, params_hash, result_document_id
            FROM documents.document_requests
            WHERE doc_type = ? AND params_hash = ? AND status = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                DocumentRequestRecord(
                    id = rs.getObject("id", UUID::class.java),
                    docType = DocumentType.valueOf(rs.getString("doc_type")),
                    status = DocumentRequestStatus.valueOf(rs.getString("status")),
                    paramsJson = rs.getString("params_json"),
                    paramsHash = rs.getString("params_hash"),
                    resultDocumentId = rs.getObject("result_document_id", UUID::class.java),
                )
            },
            docType.name,
            paramsHash,
            DocumentRequestStatus.COMPLETED.name,
        ).firstOrNull()

    fun upsertTripDocumentSet(record: TripDocumentSetRecord) {
        jdbc.update(
            """
            INSERT INTO documents.trip_document_sets (
                trip_id, manifest_doc_id, boarding_sheet_doc_id, carrier_report_doc_id,
                manifest_version, last_generated_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_id) DO UPDATE SET
                manifest_doc_id = COALESCE(EXCLUDED.manifest_doc_id, documents.trip_document_sets.manifest_doc_id),
                boarding_sheet_doc_id = COALESCE(EXCLUDED.boarding_sheet_doc_id, documents.trip_document_sets.boarding_sheet_doc_id),
                carrier_report_doc_id = COALESCE(EXCLUDED.carrier_report_doc_id, documents.trip_document_sets.carrier_report_doc_id),
                manifest_version = EXCLUDED.manifest_version,
                last_generated_at = EXCLUDED.last_generated_at,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            record.tripId,
            record.manifestDocId,
            record.boardingSheetDocId,
            record.carrierReportDocId,
            record.manifestVersion,
            record.lastGeneratedAt?.let { Timestamp.from(it) },
            Timestamp.from(Clock.systemUTC().instant()),
        )
    }

    fun findTripDocumentSet(tripId: UUID): TripDocumentSetRecord? =
        jdbc.query(
            """
            SELECT trip_id, manifest_doc_id, boarding_sheet_doc_id, carrier_report_doc_id,
                   manifest_version, last_generated_at
            FROM documents.trip_document_sets
            WHERE trip_id = ?
            """.trimIndent(),
            { rs, _ ->
                TripDocumentSetRecord(
                    tripId = rs.getObject("trip_id", UUID::class.java),
                    manifestDocId = rs.getObject("manifest_doc_id", UUID::class.java),
                    boardingSheetDocId = rs.getObject("boarding_sheet_doc_id", UUID::class.java),
                    carrierReportDocId = rs.getObject("carrier_report_doc_id", UUID::class.java),
                    manifestVersion = rs.getInt("manifest_version"),
                    lastGeneratedAt = rs.getTimestamp("last_generated_at")?.toInstant(),
                )
            },
            tripId,
        ).firstOrNull()
}
