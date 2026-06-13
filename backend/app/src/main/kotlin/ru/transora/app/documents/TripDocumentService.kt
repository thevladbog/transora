package ru.transora.app.documents

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.FontFactory
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.sales.TicketRepository
import ru.transora.app.scheduling.TripRepository
import ru.transora.scheduling.domain.Trip
import ru.transora.sales.domain.Ticket
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

@Service
class TripDocumentService(
    private val tripRepository: TripRepository,
    private val ticketRepository: TicketRepository,
    private val documentRepository: DocumentRepository,
    @Value("\${transora.documents.storage-path:./data/documents}") private val storagePath: String,
) {
    @Transactional
    fun generateManifestAndBoardingSheet(tripId: UUID): TripDocumentSetRecord {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val tickets = ticketRepository.listActiveByTripId(tripId)
        val paramsHash = hashTicketParams("manifest-boarding", tripId, tickets)

        val existing = documentRepository.findCompletedRequest(DocumentType.TRIP_MANIFEST, paramsHash)
        if (existing?.resultDocumentId != null) {
            return documentRepository.findTripDocumentSet(tripId)
                ?: throw IllegalStateException("Trip document set missing for cached manifest")
        }

        val manifest = storeDocument(
            docType = DocumentType.TRIP_MANIFEST,
            tripId = tripId,
            paramsHash = paramsHash,
            fileName = "$tripId-manifest.pdf",
            content = renderManifestPdf(trip, tickets),
        )
        val boardingSheet = storeDocument(
            docType = DocumentType.BOARDING_SHEET,
            tripId = tripId,
            paramsHash = paramsHash,
            fileName = "$tripId-boarding.pdf",
            content = renderBoardingSheetPdf(trip, tickets),
        )

        val currentSet = documentRepository.findTripDocumentSet(tripId)
        val version = (currentSet?.manifestVersion ?: 0) + 1
        val now = Clock.systemUTC().instant()
        val set = TripDocumentSetRecord(
            tripId = tripId,
            manifestDocId = manifest.id,
            boardingSheetDocId = boardingSheet.id,
            carrierReportDocId = currentSet?.carrierReportDocId,
            manifestVersion = version,
            lastGeneratedAt = now,
        )
        documentRepository.upsertTripDocumentSet(set)
        return set
    }

    @Transactional
    fun generateCarrierReport(tripId: UUID): StoredDocumentRecord {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val tickets = ticketRepository.listByTripId(tripId)
        val paramsHash = hashTicketParams("carrier-report", tripId, tickets)

        val existing = documentRepository.findCompletedRequest(DocumentType.CARRIER_REPORT, paramsHash)
        if (existing?.resultDocumentId != null) {
            return documentRepository.findById(existing.resultDocumentId)
                ?: throw IllegalStateException("Carrier report document ${existing.resultDocumentId} missing")
        }

        val report = storeDocument(
            docType = DocumentType.CARRIER_REPORT,
            tripId = tripId,
            paramsHash = paramsHash,
            fileName = "$tripId-carrier-report.pdf",
            content = renderCarrierReportPdf(trip, tickets),
        )

        val currentSet = documentRepository.findTripDocumentSet(tripId)
        documentRepository.upsertTripDocumentSet(
            TripDocumentSetRecord(
                tripId = tripId,
                manifestDocId = currentSet?.manifestDocId,
                boardingSheetDocId = currentSet?.boardingSheetDocId,
                carrierReportDocId = report.id,
                manifestVersion = currentSet?.manifestVersion ?: 0,
                lastGeneratedAt = currentSet?.lastGeneratedAt,
            ),
        )
        return report
    }

    fun getDocument(id: UUID): Pair<StoredDocumentRecord, ByteArray> {
        val record = documentRepository.findById(id)
            ?: throw NoSuchElementException("Document $id was not found")
        val content = Files.readAllBytes(Path.of(record.storagePath))
        return record to content
    }

    private fun storeDocument(
        docType: DocumentType,
        tripId: UUID,
        paramsHash: String,
        fileName: String,
        content: ByteArray,
    ): StoredDocumentRecord {
        val started = System.currentTimeMillis()
        val requestId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        val directory = Path.of(storagePath)
        Files.createDirectories(directory)
        val filePath = directory.resolve(fileName)
        Files.write(filePath, content)
        val hash = sha256(content)

        documentRepository.insertRequest(
            DocumentRequestRecord(
                id = requestId,
                docType = docType,
                status = DocumentRequestStatus.PROCESSING,
                paramsJson = """{"tripId":"$tripId"}""",
                paramsHash = paramsHash,
                resultDocumentId = null,
            ),
        )

        val record = StoredDocumentRecord(
            id = documentId,
            docType = docType,
            ticketId = null,
            tripId = tripId,
            requestId = requestId,
            contentType = "application/pdf",
            storagePath = filePath.toAbsolutePath().toString(),
            contentHash = hash,
        )
        documentRepository.insertStoredDocument(record)
        documentRepository.completeRequest(requestId, documentId, (System.currentTimeMillis() - started).toInt())
        return record
    }

    private fun renderManifestPdf(trip: Trip, tickets: List<Ticket>): ByteArray =
        renderTripListPdf("TRIP MANIFEST", trip, tickets, includePassengerNames = true)

    private fun renderBoardingSheetPdf(trip: Trip, tickets: List<Ticket>): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, output)
        document.open()

        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
        val bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10f)

        document.add(Paragraph("BOARDING SHEET", titleFont).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph("Route: ${trip.routeNumber}", bodyFont))
        document.add(Paragraph("${trip.departureStation} -> ${trip.arrivalStation}", bodyFont))
        document.add(Paragraph("Departure: ${trip.expectedDepartureTime}", bodyFont))
        document.add(Paragraph("Passengers: ${tickets.size}", bodyFont))

        val table = PdfPTable(floatArrayOf(1f, 2f, 4f))
        table.widthPercentage = 100f
        table.addCell(headerCell("Seat", bodyFont))
        table.addCell(headerCell("Ticket", bodyFont))
        table.addCell(headerCell("Barcode", bodyFont))

        tickets.forEach { ticket ->
            table.addCell(bodyCell(ticket.seatNumber.toString(), bodyFont))
            table.addCell(bodyCell(ticket.ticketNumber, bodyFont))
            val barcodeBytes = DocumentBarcodes.renderCode128(ticket.id.toString())
            val barcodeImage = Image.getInstance(barcodeBytes).apply {
                scaleToFit(180f, 30f)
            }
            table.addCell(PdfPCell(barcodeImage).apply { setPadding(4f) })
        }

        document.add(table)
        document.close()
        return output.toByteArray()
    }

    private fun headerCell(text: String, font: com.lowagie.text.Font): PdfPCell =
        PdfPCell(Paragraph(text, font)).apply {
            horizontalAlignment = Element.ALIGN_CENTER
            setPadding(4f)
        }

    private fun bodyCell(text: String, font: com.lowagie.text.Font): PdfPCell =
        PdfPCell(Paragraph(text, font)).apply { setPadding(4f) }

    private fun renderCarrierReportPdf(trip: Trip, tickets: List<Ticket>): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, output)
        document.open()
        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
        val bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 12f)
        document.add(Paragraph("CARRIER REPORT", titleFont).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph("Route: ${trip.routeNumber}", bodyFont))
        document.add(Paragraph("${trip.departureStation} -> ${trip.arrivalStation}", bodyFont))
        document.add(Paragraph("Departure: ${trip.expectedDepartureTime}", bodyFont))
        document.add(Paragraph("Tickets sold: ${tickets.size}", bodyFont))
        document.add(Paragraph("Revenue: ${tickets.sumOf { it.priceCents } / 100.0}", bodyFont))
        document.close()
        return output.toByteArray()
    }

    private fun renderTripListPdf(
        title: String,
        trip: Trip,
        tickets: List<Ticket>,
        includePassengerNames: Boolean,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document()
        PdfWriter.getInstance(document, output)
        document.open()
        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
        val bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 12f)
        document.add(Paragraph(title, titleFont).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph("Route: ${trip.routeNumber}", bodyFont))
        document.add(Paragraph("${trip.departureStation} -> ${trip.arrivalStation}", bodyFont))
        document.add(Paragraph("Departure: ${trip.expectedDepartureTime}", bodyFont))
        document.add(Paragraph("Passengers: ${tickets.size}", bodyFont))
        tickets.forEach { ticket ->
            val line = if (includePassengerNames) {
                "Seat ${ticket.seatNumber}: ${ticket.passengerName} (${ticket.ticketNumber})"
            } else {
                "Seat ${ticket.seatNumber}: ${ticket.ticketNumber}"
            }
            document.add(Paragraph(line, bodyFont))
        }
        document.close()
        return output.toByteArray()
    }

    private fun hashTicketParams(prefix: String, tripId: UUID, tickets: List<Ticket>): String {
        val ticketPart = tickets.joinToString(",") { "${it.id}:${it.status.name}" }
        return sha256("$prefix:$tripId:$ticketPart".toByteArray())
    }

    private fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return HexFormat.of().formatHex(digest)
    }
}
