package ru.transora.app.scheduling

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
import ru.transora.scheduling.domain.Route
import ru.transora.scheduling.domain.RouteStop
import ru.transora.scheduling.domain.RouteWithStops
import java.util.UUID

@RestController
@RequestMapping("/api/routes")
@Tag(name = "Routes", description = "Route management")
class RouteController(
    private val routeService: RouteService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun list(): List<RouteSummaryResponse> =
        routeService.list().map { it.toSummaryResponse() }

    @GetMapping("/{routeId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    fun get(@PathVariable routeId: UUID): RouteDetailResponse =
        routeService.get(routeId).toDetailResponse()

    @PostMapping
    @RequirePermission(Permissions.SCHEDULE_CREATE)
    fun create(@Valid @RequestBody request: CreateRouteRequest): RouteDetailResponse =
        routeService.create(request).toDetailResponse()

    @PatchMapping("/{routeId}")
    @RequirePermission(Permissions.SCHEDULE_EDIT)
    fun update(
        @PathVariable routeId: UUID,
        @Valid @RequestBody request: UpdateRouteRequest,
    ): RouteDetailResponse =
        routeService.update(routeId, request).toDetailResponse()
}

data class RouteSummaryResponse(
    val id: String,
    val carrierId: String,
    val name: String,
    val code: String?,
    val isActive: Boolean,
)

data class RouteDetailResponse(
    val id: String,
    val carrierId: String,
    val name: String,
    val code: String?,
    val description: String?,
    val isActive: Boolean,
    val stops: List<RouteStopResponse>,
)

data class RouteStopResponse(
    val id: String,
    val stopOrder: Int,
    val stopName: String,
    val stationId: String?,
    val isExternal: Boolean,
    val scheduledDurationMin: Int?,
    val dwellTimeMin: Int,
)

private fun Route.toSummaryResponse() = RouteSummaryResponse(
    id = id.toString(),
    carrierId = carrierId.toString(),
    name = name,
    code = code,
    isActive = isActive,
)

private fun RouteWithStops.toDetailResponse() = RouteDetailResponse(
    id = route.id.toString(),
    carrierId = route.carrierId.toString(),
    name = route.name,
    code = route.code,
    description = route.description,
    isActive = route.isActive,
    stops = stops.map { it.toResponse() },
)

private fun RouteStop.toResponse() = RouteStopResponse(
    id = id.toString(),
    stopOrder = stopOrder,
    stopName = stopName,
    stationId = stationId?.toString(),
    isExternal = isExternal,
    scheduledDurationMin = scheduledDurationMin,
    dwellTimeMin = dwellTimeMin,
)
