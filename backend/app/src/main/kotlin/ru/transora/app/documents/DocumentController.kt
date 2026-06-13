package ru.transora.app.documents

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api")
@Tag(name = "Documents", description = "Trip and ticket documents")
class DocumentController(
    private val tripDocumentService: TripDocumentService,
) {
    @GetMapping("/documents/{documentId}")
    @RequirePermission(Permissions.DOCUMENTS_VIEW_MANIFEST)
    @Operation(summary = "Download generated document by id")
    fun getDocument(@PathVariable documentId: UUID): ResponseEntity<ByteArray> {
        val (record, content) = tripDocumentService.getDocument(documentId)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${record.docType.name.lowercase()}-$documentId.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(content)
    }

    @PostMapping("/trips/{tripId}/documents/manifest")
    @RequirePermission(Permissions.DOCUMENTS_VIEW_MANIFEST)
    @Operation(summary = "Generate trip manifest and boarding sheet")
    fun generateManifest(@PathVariable tripId: UUID): TripDocumentsResponse {
        val set = tripDocumentService.generateManifestAndBoardingSheet(tripId)
        return TripDocumentsResponse(
            tripId = tripId.toString(),
            manifestDocumentId = set.manifestDocId?.toString(),
            boardingSheetDocumentId = set.boardingSheetDocId?.toString(),
            manifestVersion = set.manifestVersion,
            generatedAt = set.lastGeneratedAt ?: Instant.now(),
        )
    }
}

data class TripDocumentsResponse(
    val tripId: String,
    val manifestDocumentId: String?,
    val boardingSheetDocumentId: String?,
    val manifestVersion: Int,
    val generatedAt: Instant,
)
