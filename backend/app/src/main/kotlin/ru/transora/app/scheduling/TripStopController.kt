package ru.transora.app.scheduling

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.scheduling.domain.StopStatus
import ru.transora.scheduling.domain.TripStop
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/trips/{tripId}/stops")
@Tag(name = "Trip Stops", description = "Trip stop lifecycle and timing")
class TripStopController(
    private val tripStopService: TripStopService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    @Operation(summary = "List stops for a trip")
    fun list(@PathVariable tripId: UUID): List<TripStopResponse> =
        tripStopService.listStops(tripId).map { it.toResponse() }

    @PatchMapping("/{stopId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    @Operation(summary = "Update stop times or status")
    fun update(
        @PathVariable tripId: UUID,
        @PathVariable stopId: UUID,
        @Valid @RequestBody request: UpdateTripStopRequest,
    ): TripStopResponse =
        tripStopService.updateStop(tripId, stopId, request).toResponse()

    @PostMapping("/{stopId}/arrive")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    @Operation(summary = "Record actual arrival at stop")
    fun arrive(
        @PathVariable tripId: UUID,
        @PathVariable stopId: UUID,
        @RequestBody(required = false) request: RecordStopTimeRequest?,
    ): TripStopResponse =
        tripStopService.recordArrival(tripId, stopId, request?.at).toResponse()

    @PostMapping("/{stopId}/depart")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    @Operation(summary = "Record actual departure from stop")
    fun depart(
        @PathVariable tripId: UUID,
        @PathVariable stopId: UUID,
        @RequestBody(required = false) request: RecordStopTimeRequest?,
    ): TripStopResponse =
        tripStopService.recordDeparture(tripId, stopId, request?.at).toResponse()
}

data class RecordStopTimeRequest(
    val at: Instant? = null,
)

data class TripStopResponse(
    val id: String,
    val tripId: String,
    val stopOrder: Int,
    val stopName: String,
    val stationId: String?,
    val isExternal: Boolean,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant,
    val estimatedArrival: Instant?,
    val estimatedDeparture: Instant?,
    val actualArrival: Instant?,
    val actualDeparture: Instant?,
    val stopStatus: String,
)

private fun TripStop.toResponse() = TripStopResponse(
    id = id.toString(),
    tripId = tripId.toString(),
    stopOrder = stopOrder,
    stopName = stopName,
    stationId = stationId?.toString(),
    isExternal = isExternal,
    scheduledArrival = scheduledArrival,
    scheduledDeparture = scheduledDeparture,
    estimatedArrival = estimatedArrival,
    estimatedDeparture = estimatedDeparture,
    actualArrival = actualArrival,
    actualDeparture = actualDeparture,
    stopStatus = stopStatus.name,
)
