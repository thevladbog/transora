package ru.transora.app.inventory

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/trips/{tripId}/seats")
class SeatController(
    private val seatService: SeatService,
) {
    @GetMapping
    @RequirePermission(Permissions.INVENTORY_VIEW)
    @Operation(summary = "Seat availability (legacy list or station-scoped with toStopOrder)")
    fun list(
        @PathVariable tripId: UUID,
        @RequestParam(required = false) toStopOrder: Int?,
    ): Any =
        if (toStopOrder != null) {
            seatService.listSeatsForStation(tripId, toStopOrder).toResponse()
        } else {
            seatService.listSeats(tripId).map { it.toResponse() }
        }
}

data class SeatAvailabilityResponse(
    val tripId: String,
    val seatNumber: Int,
    val status: String,
    val requiresReaccommodation: Boolean = false,
)

data class StationSeatMapResponse(
    val tripId: String,
    val stationId: String?,
    val requestedAt: Instant,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val seats: List<StationSeatEntryResponse>,
    val transitGate: TransitGateSummaryResponse?,
)

data class StationSeatEntryResponse(
    val seatNumber: Int,
    val status: String,
    val availableForStation: Boolean,
    val restrictionReason: String?,
)

data class TransitGateSummaryResponse(
    val status: String,
    val availableSeats: List<Int>?,
    val openedAt: Instant?,
)

private fun SeatAvailability.toResponse(): SeatAvailabilityResponse =
    SeatAvailabilityResponse(
        tripId = tripId.toString(),
        seatNumber = seatNumber,
        status = status.name,
        requiresReaccommodation = requiresReaccommodation,
    )

private fun StationSeatView.toResponse(): StationSeatMapResponse =
    StationSeatMapResponse(
        tripId = tripId.toString(),
        stationId = stationId?.toString(),
        requestedAt = requestedAt,
        fromStopOrder = fromStopOrder,
        toStopOrder = toStopOrder,
        seats = seats.map {
            StationSeatEntryResponse(
                seatNumber = it.seatNumber,
                status = it.status,
                availableForStation = it.availableForStation,
                restrictionReason = it.restrictionReason,
            )
        },
        transitGate = transitGate?.let {
            TransitGateSummaryResponse(
                status = it.status,
                availableSeats = it.availableSeats,
                openedAt = it.openedAt,
            )
        },
    )
