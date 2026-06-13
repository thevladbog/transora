package ru.transora.app.dispatcher

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.app.inventory.TransitGateRecord
import ru.transora.app.inventory.TransitGateService
import ru.transora.iam.permissions.Permissions
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api")
@Tag(name = "Dispatcher", description = "Dispatcher station operations")
class DispatcherController(
    private val dispatcherService: DispatcherService,
    private val transitGateService: TransitGateService,
) {
    @PostMapping("/sales-restrictions")
    @RequirePermission(Permissions.INVENTORY_TOGGLE)
    @Operation(summary = "Create sales restriction for current station (trip or schedule entry scope)")
    fun createSalesRestriction(@Valid @RequestBody request: CreateSalesRestrictionRequest): SalesRestrictionResponse =
        dispatcherService.createSalesRestriction(request)

    @PostMapping("/sales-restrictions/{id}/pause")
    @RequirePermission(Permissions.INVENTORY_TOGGLE)
    @Operation(summary = "Pause an active sales restriction")
    fun pauseSalesRestriction(@PathVariable id: UUID): SalesRestrictionResponse =
        dispatcherService.pauseSalesRestriction(id)

    @PostMapping("/sales-restrictions/{id}/resume")
    @RequirePermission(Permissions.INVENTORY_TOGGLE)
    @Operation(summary = "Resume a paused sales restriction")
    fun resumeSalesRestriction(@PathVariable id: UUID): SalesRestrictionResponse =
        dispatcherService.resumeSalesRestriction(id)

    @PostMapping("/seat-blocks")
    @RequirePermission(Permissions.INVENTORY_MANUAL_BLOCK)
    @Operation(summary = "Manually block a seat on a trip")
    fun createSeatBlock(@Valid @RequestBody request: CreateSeatBlockRequest): SeatBlockResponse =
        dispatcherService.createSeatBlock(request)

    @PostMapping("/seat-blocks/{id}/release")
    @RequirePermission(Permissions.INVENTORY_MANUAL_BLOCK)
    @Operation(summary = "Release a manual seat block")
    fun releaseSeatBlock(@PathVariable id: UUID): SeatBlockResponse =
        dispatcherService.releaseSeatBlock(id)

    @GetMapping("/transit-gates")
    @RequirePermission(Permissions.INVENTORY_TRANSIT_GATE)
    @Operation(summary = "List transit gates for a trip at current station")
    fun listTransitGates(@RequestParam tripId: UUID): List<TransitGateResponse> =
        transitGateService.listForTrip(tripId).map { it.toResponse() }

    @PostMapping("/transit-gates/{gateId}/open")
    @RequirePermission(Permissions.INVENTORY_TRANSIT_GATE)
    @Operation(summary = "Open transit sales after bus arrival")
    fun openTransitGate(
        @PathVariable gateId: UUID,
        @Valid @RequestBody request: OpenTransitGateRequest,
    ): TransitGateResponse =
        transitGateService.openGate(gateId, request.availableSeats, request.notes).toResponse()

    @PostMapping("/transit-gates/{gateId}/close")
    @RequirePermission(Permissions.INVENTORY_CLOSE_TRANSIT_GATE)
    @Operation(summary = "Close transit sales after boarding completed")
    fun closeTransitGate(
        @PathVariable gateId: UUID,
        @Valid @RequestBody request: CloseTransitGateRequest,
    ): TransitGateResponse =
        transitGateService.closeGate(gateId, request.notes).toResponse()
}

data class CreateSalesRestrictionRequest(
    val tripId: UUID? = null,
    val scheduleEntryId: UUID? = null,
    @field:NotEmpty val allowedSeats: List<Int>,
    val scope: String? = null,
)

data class CreateSeatBlockRequest(
    @field:NotNull val tripId: UUID?,
    @field:Min(1) val seatNumber: Int,
    val blockType: String? = null,
    val reason: String? = null,
)

data class OpenTransitGateRequest(
    @field:NotEmpty val availableSeats: List<Int>,
    val notes: String? = null,
)

data class CloseTransitGateRequest(
    val notes: String? = null,
)

data class SalesRestrictionResponse(
    val id: String,
    val tripId: String?,
    val scheduleEntryId: String?,
    val stationId: String?,
    val allowedSeats: List<Int>,
    val status: String,
    val scope: String,
    val createdAt: Instant,
)

data class SeatBlockResponse(
    val id: String,
    val tripId: String,
    val seatNumber: Int,
    val blockType: String,
    val reason: String?,
    val createdAt: Instant,
    val releasedAt: Instant? = null,
)

data class TransitGateResponse(
    val id: String,
    val tripId: String,
    val stationId: String,
    val stopOrder: Int,
    val status: String,
    val availableSeats: List<Int>?,
    val openedAt: Instant?,
    val closedAt: Instant?,
    val notes: String?,
    val createdAt: Instant,
)

private fun TransitGateRecord.toResponse() = TransitGateResponse(
    id = id.toString(),
    tripId = tripId.toString(),
    stationId = stationId.toString(),
    stopOrder = stopOrder,
    status = status.name,
    availableSeats = availableSeats,
    openedAt = openedAt,
    closedAt = closedAt,
    notes = notes,
    createdAt = createdAt,
)
