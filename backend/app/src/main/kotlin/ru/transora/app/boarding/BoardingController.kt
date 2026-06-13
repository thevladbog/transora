package ru.transora.app.boarding

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/boarding")
@Tag(name = "Boarding", description = "Ticket boarding scan and sync")
class BoardingController(
    private val boardingService: BoardingService,
) {
    @PostMapping("/scan")
    @RequirePermission(Permissions.BOARDING_SCAN)
    @Operation(summary = "Scan ticket and mark as USED (idempotent)")
    fun scan(@Valid @RequestBody request: BoardingScanRequest): BoardingScanResponse =
        boardingService.scan(request)

    @PostMapping("/sync")
    @RequirePermission(Permissions.BOARDING_SCAN)
    @Operation(summary = "Batch sync offline boarding events")
    fun sync(@Valid @RequestBody request: BoardingSyncRequest): BoardingSyncResponse =
        boardingService.syncBatch(request)

    @GetMapping("/trips/{tripId}/tickets")
    @RequirePermission(Permissions.BOARDING_SCAN)
    @Operation(summary = "Ticket manifest for offline boarding cache")
    fun tripTickets(@PathVariable tripId: UUID): BoardingTicketManifestResponse =
        boardingService.listTicketsForTrip(tripId)
}

@RestController
@RequestMapping("/api/trips")
class TripBoardingController(
    private val boardingService: BoardingService,
) {
    @GetMapping("/{tripId}/boarding/stats")
    @RequirePermission(Permissions.BOARDING_VIEW_STATS)
    @Operation(summary = "Boarding statistics for a trip")
    fun stats(@PathVariable tripId: UUID): BoardingStatsResponse =
        boardingService.stats(tripId)
}

data class BoardingScanRequest(
    val ticketId: UUID? = null,
    val ticketNumber: String? = null,
    val tripId: UUID? = null,
    val clientEventId: String? = null,
)

data class BoardingScanResponse(
    val ticketId: String,
    val tripId: String,
    val scanResult: String,
    val ticketStatus: String?,
    val alreadyProcessed: Boolean,
)

data class BoardingStatsResponse(
    val tripId: String,
    val totalTickets: Int,
    val boardedCount: Int,
    val pendingCount: Int,
    val refundedCount: Int,
)

data class BoardingSyncRequest(
    @field:NotEmpty val events: List<BoardingSyncEventRequest>,
)

data class BoardingSyncEventRequest(
    val ticketId: UUID? = null,
    val ticketNumber: String? = null,
    val tripId: UUID? = null,
    val clientEventId: String? = null,
)

data class BoardingSyncResponse(
    val batchId: String,
    val results: List<BoardingSyncEventResult>,
)

data class BoardingSyncEventResult(
    val clientEventId: String?,
    val ticketId: String,
    val scanResult: String,
    val duplicate: Boolean,
)

data class BoardingTicketManifestResponse(
    val tripId: String,
    val tickets: List<BoardingTicketManifestRow>,
)

data class BoardingTicketManifestRow(
    val ticketId: String,
    val ticketNumber: String,
    val tripId: String,
    val status: String,
    val passengerName: String,
    val seatNumber: Int,
)
