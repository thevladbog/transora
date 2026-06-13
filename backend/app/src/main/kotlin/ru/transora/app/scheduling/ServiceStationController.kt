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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import ru.transora.app.notifications.BoardTripQueryService
import ru.transora.app.notifications.StationBoardTrip
import ru.transora.scheduling.domain.ServiceStation
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/stations")
@Tag(name = "Stations", description = "Service station management")
class ServiceStationController(
    private val serviceStationService: ServiceStationService,
    private val boardTripQueryService: BoardTripQueryService,
    private val tripGenerationService: TripGenerationService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<ServiceStationResponse> =
        serviceStationService.list().map { it.toResponse() }

    @GetMapping("/{stationId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable stationId: UUID): ServiceStationResponse =
        serviceStationService.get(stationId).toResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateServiceStationRequest): ServiceStationResponse =
        serviceStationService.create(request).toResponse()

    @PatchMapping("/{stationId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable stationId: UUID,
        @Valid @RequestBody request: UpdateServiceStationRequest,
    ): ServiceStationResponse =
        serviceStationService.update(stationId, request).toResponse()

    @PostMapping("/{code}/schedules/generate")
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    @Operation(summary = "Generate trips for routes passing through this station")
    fun generateForStation(
        @PathVariable code: String,
        @RequestParam(required = false) fromDate: LocalDate?,
        @RequestParam(required = false) horizonDays: Int?,
    ): TripGenerationResult {
        val station = serviceStationService.getByCode(code)
        return tripGenerationService.generate(fromDate, horizonDays, station.id)
    }

    @GetMapping("/{code}/trips")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    @Operation(summary = "List trips for station board window")
    fun tripsForBoard(
        @PathVariable code: String,
        @RequestParam(required = false) windowBeforeMin: Int?,
        @RequestParam(required = false) windowAfterMin: Int?,
    ): StationTripsResponse {
        serviceStationService.getByCode(code)
        val stationCode = code.trim().uppercase()
        val trips = boardTripQueryService
            .listForStation(stationCode, windowBeforeMin, windowAfterMin)
            .map { it.toBoardTripResponse() }
        return StationTripsResponse(
            stationCode = stationCode,
            generatedAt = java.time.Instant.now(),
            trips = trips,
        )
    }
}

data class ServiceStationResponse(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val timezone: String,
    val address: String?,
    val isActive: Boolean,
)

data class StationTripsResponse(
    val stationCode: String,
    val generatedAt: java.time.Instant,
    val trips: List<BoardTripResponse>,
)

data class BoardTripResponse(
    val id: String,
    val routeNumber: String,
    val tripNumber: String,
    val direction: String,
    val stopOrder: Int,
    val departureStation: String,
    val arrivalStation: String,
    val displayTime: java.time.Instant,
    val expectedDepartureTime: java.time.Instant,
    val platform: String?,
    val status: String,
    val delayMinutes: Int?,
)

private fun ServiceStation.toResponse() = ServiceStationResponse(
    id = id.toString(),
    code = code,
    name = name,
    city = city,
    timezone = timezone,
    address = address,
    isActive = isActive,
)

private fun StationBoardTrip.toBoardTripResponse() = BoardTripResponse(
    id = tripId.toString(),
    routeNumber = routeNumber,
    tripNumber = tripNumber,
    direction = direction.name,
    stopOrder = stopOrder,
    departureStation = originLabel,
    arrivalStation = destinationLabel,
    displayTime = displayTime,
    expectedDepartureTime = displayTime,
    platform = platform,
    status = tripStatus.name,
    delayMinutes = delayMinutes,
)
