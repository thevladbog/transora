package ru.transora.app.boarding

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.admin.AuditLogService
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentUserId
import ru.transora.app.outbox.OutboxEventRepository
import ru.transora.app.sales.TicketRepository
import ru.transora.boarding.domain.BoardingScanEvent
import ru.transora.boarding.domain.BoardingStats
import ru.transora.boarding.domain.ScanResult
import ru.transora.sales.domain.TicketStatus
import java.time.Clock
import java.util.UUID

@Service
class BoardingService(
    private val boardingRepository: BoardingRepository,
    private val ticketRepository: TicketRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val auditLogService: AuditLogService,
) {
    @Transactional
    fun scan(request: BoardingScanRequest): BoardingScanResponse {
        val stationId = StationScope.requireStationId()
        val scannedBy = currentUserId()
        val ticket = when {
            request.ticketId != null -> ticketRepository.findById(request.ticketId)
            request.ticketNumber != null -> ticketRepository.findByTicketNumber(request.ticketNumber)
            else -> throw DomainRuleViolation("ticketId or ticketNumber is required")
        } ?: return responseFor(
            ticketId = request.ticketId ?: UUID(0, 0),
            tripId = request.tripId ?: UUID(0, 0),
            scanResult = ScanResult.INVALID_TICKET,
            ticketStatus = null,
            alreadyProcessed = false,
        )

        if (request.tripId != null && ticket.tripId != request.tripId) {
            recordScan(ticket.id, ticket.tripId, stationId, scannedBy, ScanResult.WRONG_TRIP, request.clientEventId)
            return responseFor(ticket.id, ticket.tripId, ScanResult.WRONG_TRIP, ticket.status.name, false)
        }

        when (ticket.status) {
            TicketStatus.USED -> {
                recordScan(ticket.id, ticket.tripId, stationId, scannedBy, ScanResult.ALREADY_USED, request.clientEventId)
                return responseFor(ticket.id, ticket.tripId, ScanResult.ALREADY_USED, ticket.status.name, true)
            }
            TicketStatus.REFUNDED -> {
                recordScan(ticket.id, ticket.tripId, stationId, scannedBy, ScanResult.REFUNDED, request.clientEventId)
                return responseFor(ticket.id, ticket.tripId, ScanResult.REFUNDED, ticket.status.name, false)
            }
            TicketStatus.ISSUED -> {
                ticketRepository.updateStatus(ticket.id, TicketStatus.USED)
                recordScan(ticket.id, ticket.tripId, stationId, scannedBy, ScanResult.BOARDED, request.clientEventId)
                outboxEventRepository.append(
                    aggregateType = "ticket",
                    aggregateId = ticket.id.toString(),
                    eventType = "ticket.used",
                    payload = mapOf(
                        "ticketId" to ticket.id,
                        "tripId" to ticket.tripId,
                        "stationId" to stationId,
                        "scannedBy" to scannedBy,
                    ),
                )
                auditLogService.record(
                    module = "boarding",
                    action = "ticket.scanned",
                    entityType = "ticket",
                    entityId = ticket.id.toString(),
                )
                return responseFor(ticket.id, ticket.tripId, ScanResult.BOARDED, TicketStatus.USED.name, false)
            }
        }
    }

    fun stats(tripId: UUID): BoardingStatsResponse {
        val stats = boardingRepository.statsForTrip(tripId)
        return stats.toResponse()
    }

    fun listTicketsForTrip(tripId: UUID): BoardingTicketManifestResponse {
        val tickets = ticketRepository.listByTripId(tripId)
        return BoardingTicketManifestResponse(
            tripId = tripId.toString(),
            tickets = tickets.map { ticket ->
                BoardingTicketManifestRow(
                    ticketId = ticket.id.toString(),
                    ticketNumber = ticket.ticketNumber,
                    tripId = ticket.tripId.toString(),
                    status = ticket.status.name,
                    passengerName = ticket.passengerName,
                    seatNumber = ticket.seatNumber,
                )
            },
        )
    }

    @Transactional
    fun syncBatch(request: BoardingSyncRequest): BoardingSyncResponse {
        val stationId = StationScope.requireStationId()
        val syncedBy = currentUserId()
        val results = request.events.map { event ->
            if (event.clientEventId != null) {
                boardingRepository.findByClientEventId(event.clientEventId)?.let { existing ->
                    return@map BoardingSyncEventResult(
                        clientEventId = event.clientEventId,
                        ticketId = existing.ticketId.toString(),
                        scanResult = existing.scanResult.name,
                        duplicate = true,
                    )
                }
            }
            val scanResult = scan(
                BoardingScanRequest(
                    ticketId = event.ticketId,
                    ticketNumber = event.ticketNumber,
                    tripId = event.tripId,
                    clientEventId = event.clientEventId,
                ),
            )
            BoardingSyncEventResult(
                clientEventId = event.clientEventId,
                ticketId = scanResult.ticketId,
                scanResult = scanResult.scanResult,
                duplicate = false,
            )
        }
        val batchId = UUID.randomUUID()
        boardingRepository.insertSyncBatch(batchId, stationId, syncedBy, results.size)
        return BoardingSyncResponse(batchId = batchId.toString(), results = results)
    }

    private fun recordScan(
        ticketId: UUID,
        tripId: UUID,
        stationId: UUID,
        scannedBy: UUID,
        scanResult: ScanResult,
        clientEventId: String?,
    ) {
        if (clientEventId != null && boardingRepository.findByClientEventId(clientEventId) != null) {
            return
        }
        boardingRepository.insertScanEvent(
            BoardingScanEvent(
                id = UUID.randomUUID(),
                ticketId = ticketId,
                tripId = tripId,
                stationId = stationId,
                scannedBy = scannedBy,
                scanResult = scanResult,
                scannedAt = Clock.systemUTC().instant(),
                clientEventId = clientEventId,
            ),
        )
    }

    private fun responseFor(
        ticketId: UUID,
        tripId: UUID,
        scanResult: ScanResult,
        ticketStatus: String?,
        alreadyProcessed: Boolean,
    ) = BoardingScanResponse(
        ticketId = ticketId.toString(),
        tripId = tripId.toString(),
        scanResult = scanResult.name,
        ticketStatus = ticketStatus,
        alreadyProcessed = alreadyProcessed,
    )
}

private fun BoardingStats.toResponse() = BoardingStatsResponse(
    tripId = tripId.toString(),
    totalTickets = totalTickets,
    boardedCount = boardedCount,
    pendingCount = pendingCount,
    refundedCount = refundedCount,
)
