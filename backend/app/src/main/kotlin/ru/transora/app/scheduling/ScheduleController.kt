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
import ru.transora.scheduling.domain.Schedule
import ru.transora.scheduling.domain.ScheduleEntry
import ru.transora.scheduling.domain.ScheduleWithEntries
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RestController
@RequestMapping("/api/schedules")
@Tag(name = "Schedules", description = "Schedule templates and trip generation")
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val tripGenerationService: TripGenerationService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<ScheduleSummaryResponse> =
        scheduleService.list().map { it.toSummaryResponse() }

    @GetMapping("/{scheduleId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable scheduleId: UUID): ScheduleDetailResponse =
        scheduleService.get(scheduleId).toDetailResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateScheduleRequest): ScheduleDetailResponse =
        scheduleService.create(request).toDetailResponse()

    @PatchMapping("/{scheduleId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable scheduleId: UUID,
        @Valid @RequestBody request: UpdateScheduleRequest,
    ): ScheduleDetailResponse =
        scheduleService.update(scheduleId, request).toDetailResponse()

    @PostMapping("/generate")
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    @Operation(summary = "Trigger trip generation for the configured horizon")
    fun generate(
        @RequestParam(required = false) fromDate: LocalDate?,
        @RequestParam(required = false) horizonDays: Int?,
    ): TripGenerationResult =
        tripGenerationService.generate(fromDate, horizonDays)
}

data class ScheduleSummaryResponse(
    val id: String,
    val routeId: String,
    val name: String,
    val scheduleType: String,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val isActive: Boolean,
)

data class ScheduleDetailResponse(
    val id: String,
    val routeId: String,
    val name: String,
    val scheduleType: String,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val isActive: Boolean,
    val entries: List<ScheduleEntryResponse>,
)

data class ScheduleEntryResponse(
    val id: String,
    val tripNumber: String,
    val departureTime: LocalTime,
    val daysOfWeek: List<Int>,
    val defaultVehicleId: String?,
    val isActive: Boolean,
)

private fun Schedule.toSummaryResponse() = ScheduleSummaryResponse(
    id = id.toString(),
    routeId = routeId.toString(),
    name = name,
    scheduleType = scheduleType.name,
    validFrom = validFrom,
    validTo = validTo,
    isActive = isActive,
)

private fun ScheduleWithEntries.toDetailResponse() = ScheduleDetailResponse(
    id = schedule.id.toString(),
    routeId = schedule.routeId.toString(),
    name = schedule.name,
    scheduleType = schedule.scheduleType.name,
    validFrom = schedule.validFrom,
    validTo = schedule.validTo,
    isActive = schedule.isActive,
    entries = entries.map { it.toResponse() },
)

private fun ScheduleEntry.toResponse() = ScheduleEntryResponse(
    id = id.toString(),
    tripNumber = tripNumber,
    departureTime = departureTime,
    daysOfWeek = daysOfWeek,
    defaultVehicleId = defaultVehicleId?.toString(),
    isActive = isActive,
)
