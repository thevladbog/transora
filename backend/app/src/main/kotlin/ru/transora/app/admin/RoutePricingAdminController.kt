package ru.transora.app.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/route-pricing")
@Tag(name = "Admin Route Pricing", description = "Unified route editor: scheduling route + tariff matrix")
class RoutePricingAdminController(
    private val routePricingAdminService: RoutePricingAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    @Operation(summary = "List routes with pricing summary")
    fun list(): List<RoutePricingSummaryResponse> = routePricingAdminService.list()

    @GetMapping("/{routeId}")
    @RequirePermission(Permissions.SCHEDULE_VIEW)
    @Operation(summary = "Route pricing bundle")
    fun get(@PathVariable routeId: UUID): RoutePricingBundleResponse = routePricingAdminService.get(routeId)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Create route with tariff profile")
    fun create(@Valid @RequestBody request: CreateRoutePricingRequest): RoutePricingBundleResponse =
        routePricingAdminService.create(request)

    @PutMapping("/{routeId}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Update route meta and profile settings")
    fun update(
        @PathVariable routeId: UUID,
        @Valid @RequestBody request: UpdateRoutePricingRequest,
    ): RoutePricingBundleResponse = routePricingAdminService.updateMeta(routeId, request)

    @PutMapping("/{routeId}/stops")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Sync route and tariff profile stops from points")
    fun syncStops(
        @PathVariable routeId: UUID,
        @Valid @RequestBody request: SyncRouteStopsRequest,
    ): RoutePricingBundleResponse = routePricingAdminService.syncStops(routeId, request)

    @PutMapping("/{routeId}/matrix")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Upsert tariff matrix for route")
    fun upsertMatrix(
        @PathVariable routeId: UUID,
        @Valid @RequestBody request: UpsertTariffMatrixRequest,
    ): RoutePricingBundleResponse = routePricingAdminService.upsertMatrix(routeId, request)

    @DeleteMapping("/{routeId}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Deactivate route")
    fun deactivate(@PathVariable routeId: UUID): RoutePricingBundleResponse =
        routePricingAdminService.deactivate(routeId)
}

data class CreateRoutePricingRequest(
    val carrierId: UUID,
    @field:NotBlank val code: String,
    @field:NotBlank val routeNumber: String,
    @field:NotBlank val name: String,
    val description: String? = null,
)

data class UpdateRoutePricingRequest(
    val carrierId: UUID? = null,
    val code: String? = null,
    val routeNumber: String? = null,
    val name: String? = null,
    val description: String? = null,
    val isActive: Boolean? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
)

data class SyncRouteStopsRequest(
    val pointIds: List<UUID> = emptyList(),
    val legDurationsMin: List<Int>? = null,
)

data class RoutePricingSummaryResponse(
    val routeId: String,
    val carrierId: String,
    val carrierName: String?,
    val code: String?,
    val routeNumber: String,
    val name: String,
    val isActive: Boolean,
    val tariffProfileId: String?,
    val stopCount: Int,
    val matrixCellCount: Int,
)

data class RoutePricingStopResponse(
    val stopOrder: Int,
    val pointId: String,
    val pointCode: String?,
    val pointName: String?,
    val pointCity: String?,
    val stationId: String?,
    val stationCode: String?,
    val isBranch: Boolean,
    val scheduledDurationMin: Int?,
)

data class RoutePricingBundleResponse(
    val routeId: String,
    val carrierId: String,
    val code: String?,
    val routeNumber: String,
    val name: String,
    val description: String?,
    val isActive: Boolean,
    val tariffProfileId: String,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val stops: List<RoutePricingStopResponse>,
    val cells: List<TariffCellResponse>,
    val distanceKm: Double?,
    val distanceSource: String?,
    val durationMin: Int?,
    val legs: List<RouteLegDistanceResponse>,
)

data class RouteLegDistanceResponse(
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val distanceKm: Double,
    val durationMin: Int?,
)
