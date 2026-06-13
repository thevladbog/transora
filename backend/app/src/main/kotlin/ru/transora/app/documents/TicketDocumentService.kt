package ru.transora.app.documents

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.FontFactory
import com.lowagie.text.Image
import com.lowagie.text.Paragraph
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.sales.TicketRepository
import ru.transora.app.scheduling.TripRepository
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

data class TicketDocument(
    val ticketId: UUID,
    val content: ByteArray,
    val documentId: UUID?,
)

data class PrintContext(
    val printedBy: String,
    val stationCode: String? = null,
    val posId: String? = null,
)

@Service
class TicketDocumentService(
    private val ticketRepository: TicketRepository,
    private val tripRepository: TripRepository,
    private val generatedDocumentRepository: GeneratedDocumentRepository,
    private val printLogRepository: PrintLogRepository,
    @Value("\${transora.documents.storage-path:./data/documents}") private val storagePath: String,
) {
    fun getOrGenerate(ticketId: UUID): TicketDocument {
        val existing = generatedDocumentRepository.findByTicketId(ticketId)
        if (existing != null) {
            val content = Files.readAllBytes(Path.of(existing.storagePath))
            return TicketDocument(ticketId, content, existing.id)
        }
        val content = generateForTicket(ticketId)
        val generated = generatedDocumentRepository.findByTicketId(ticketId)
        return TicketDocument(ticketId, content, generated?.id)
    }

    @Transactional
    fun logPrint(ticketId: UUID, documentId: UUID, tripId: UUID, context: PrintContext) {
        val printType = if (printLogRepository.countByTicketId(ticketId) == 0) {
            PrintType.TICKET_PRINT
        } else {
            PrintType.TICKET_REPRINT
        }
        printLogRepository.insert(
            PrintLogRecord(
                id = UUID.randomUUID(),
                documentId = documentId,
                ticketId = ticketId,
                tripId = tripId,
                printedBy = context.printedBy,
                stationCode = context.stationCode,
                posId = context.posId,
                printType = printType,
            ),
        )
    }

    @Transactional
    fun generateVoidedTicket(ticketId: UUID): ByteArray {
        generatedDocumentRepository.deleteByTicketId(ticketId)
        return generateForTicket(ticketId, voided = true)
    }

    @Transactional
    fun generateForTicket(ticketId: UUID, voided: Boolean = false): ByteArray {
        if (!voided) {
            generatedDocumentRepository.findByTicketId(ticketId)?.let { existing ->
                return Files.readAllBytes(Path.of(existing.storagePath))
            }
        }

        val ticket = ticketRepository.findById(ticketId)
            ?: throw NoSuchElementException("Ticket $ticketId was not found")
        val trip = tripRepository.findById(ticket.tripId)
            ?: throw NoSuchElementException("Trip ${ticket.tripId} was not found")

        val pdfBytes = renderPdf(ticket, trip, voided)
        val hash = sha256(pdfBytes)
        val directory = Path.of(storagePath)
        Files.createDirectories(directory)
        val filePath = directory.resolve("$ticketId.pdf")
        Files.write(filePath, pdfBytes)

        generatedDocumentRepository.insert(
            GeneratedDocumentRecord(
                id = UUID.randomUUID(),
                ticketId = ticketId,
                contentType = "application/pdf",
                storagePath = filePath.toAbsolutePath().toString(),
                contentHash = hash,
            ),
        )

        return pdfBytes
    }

    private fun renderPdf(
        ticket: ru.transora.sales.domain.Ticket,
        trip: ru.transora.scheduling.domain.Trip,
        voided: Boolean = false,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val pageSize = Rectangle(DocumentBarcodes.THERMAL_WIDTH_PT, DocumentBarcodes.THERMAL_MAX_HEIGHT_PT)
        val document = Document(pageSize, 8f, 8f, 8f, 8f)
        PdfWriter.getInstance(document, output)
        document.open()

        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f)
        val bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 8f)
        val voidFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14f)

        if (voided) {
            document.add(Paragraph("АННУЛИРОВАН", voidFont).apply { alignment = Element.ALIGN_CENTER })
        }
        document.add(Paragraph("TRANSORA", titleFont).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph(ticket.ticketNumber, bodyFont).apply { alignment = Element.ALIGN_CENTER })
        document.add(Paragraph(ticket.passengerName, bodyFont))
        document.add(Paragraph("Маршрут ${trip.routeNumber}", bodyFont))
        document.add(Paragraph("${trip.departureStation} -> ${trip.arrivalStation}", bodyFont))
        document.add(Paragraph("Место: ${ticket.seatNumber}", bodyFont))
        document.add(
            Paragraph(
                "Цена: ${ticket.priceCents / 100}.${(ticket.priceCents % 100).toString().padStart(2, '0')}",
                bodyFont,
            ),
        )
        document.add(Paragraph("Отправление: ${trip.expectedDepartureTime}", bodyFont))

        val qrPayload = TicketQrPayload.build(ticket)
        val qrBytes = DocumentBarcodes.renderQrCode(qrPayload)
        document.add(
            Image.getInstance(qrBytes).apply {
                scaleToFit(DocumentBarcodes.QR_SIZE_PT, DocumentBarcodes.QR_SIZE_PT)
                alignment = Element.ALIGN_CENTER
            },
        )

        document.close()
        return output.toByteArray()
    }

    private fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        return HexFormat.of().formatHex(digest)
    }
}
