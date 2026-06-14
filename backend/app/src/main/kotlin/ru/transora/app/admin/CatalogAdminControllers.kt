package ru.transora.app.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/points")
@Tag(name = "Admin Points")
class PointAdminController(
    private val pointAdminService: PointAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun list(): List<PointResponse> = pointAdminService.list()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun get(@PathVariable id: UUID): PointResponse = pointAdminService.get(id)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun create(@Valid @RequestBody request: UpsertPointRequest): PointResponse = pointAdminService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpsertPointRequest): PointResponse =
        pointAdminService.update(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun delete(@PathVariable id: UUID) = pointAdminService.delete(id)
}

@RestController
@RequestMapping("/api/admin/geocode")
@Tag(name = "Admin Geocode")
class GeocodeController(
    private val geocodeService: GeocodeService,
) {
    @GetMapping("/search")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Search addresses via Nominatim")
    fun search(
        @RequestParam q: String,
        @RequestParam(required = false) city: String?,
        @RequestParam(defaultValue = "10") @Min(1) limit: Int,
    ): List<GeocodeResult> = geocodeService.search(q, limit, city)

    @GetMapping("/reverse")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    @Operation(summary = "Reverse geocode coordinates")
    fun reverse(
        @RequestParam lat: Double,
        @RequestParam lon: Double,
    ): GeocodeResult? = geocodeService.reverse(lat, lon)
}

@RestController
@RequestMapping("/api/admin/nomenclature")
@Tag(name = "Admin Nomenclature")
class NomenclatureAdminController(
    private val nomenclatureAdminService: NomenclatureAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun list(): List<NomenclatureResponse> = nomenclatureAdminService.list()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun get(@PathVariable id: UUID): NomenclatureResponse = nomenclatureAdminService.get(id)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun create(@Valid @RequestBody request: UpsertNomenclatureRequest): NomenclatureResponse =
        nomenclatureAdminService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpsertNomenclatureRequest): NomenclatureResponse =
        nomenclatureAdminService.update(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun delete(@PathVariable id: UUID) = nomenclatureAdminService.delete(id)
}

@RestController
@RequestMapping("/api/admin/tariff-profiles")
@Tag(name = "Admin Tariff Profiles")
class TariffProfileAdminController(
    private val tariffProfileAdminService: TariffProfileAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun list(): List<TariffProfileResponse> = tariffProfileAdminService.list()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun get(@PathVariable id: UUID): TariffProfileResponse = tariffProfileAdminService.get(id)

    @GetMapping("/{id}/matrix")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun getMatrix(@PathVariable id: UUID): TariffMatrixResponse = tariffProfileAdminService.getMatrix(id)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun create(@Valid @RequestBody request: UpsertTariffProfileRequest): TariffProfileResponse =
        tariffProfileAdminService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpsertTariffProfileRequest): TariffProfileResponse =
        tariffProfileAdminService.update(id, request)

    @PutMapping("/{id}/stops")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun replaceStops(@PathVariable id: UUID, @Valid @RequestBody request: ReplaceTariffStopsRequest): TariffMatrixResponse =
        tariffProfileAdminService.replaceStops(id, request.pointIds)

    @PutMapping("/{id}/matrix")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun upsertMatrix(@PathVariable id: UUID, @Valid @RequestBody request: UpsertTariffMatrixRequest): TariffMatrixResponse =
        tariffProfileAdminService.upsertMatrix(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun delete(@PathVariable id: UUID) = tariffProfileAdminService.delete(id)
}

data class UpsertPointRequest(
    val code: String = "",
    @field:NotBlank val name: String,
    val city: String? = null,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    val isActive: Boolean? = null,
)

data class PointResponse(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class UpsertNomenclatureRequest(
    @field:NotBlank val code: String,
    @field:NotBlank val name: String,
    @field:NotBlank val category: String,
    @field:Min(0) val priceCents: Long,
    val refundPolicyId: UUID? = null,
    val isActive: Boolean? = null,
    val description: String? = null,
    val saleMode: String? = null,
    val pricingMode: String? = null,
    val routePercent: BigDecimal? = null,
    val minPriceCents: Long? = null,
    val maxPriceCents: Long? = null,
    val maxQtyPerTicket: Int? = null,
    val refundAllowed: Boolean? = null,
    val printName: String? = null,
    val ffdPaymentObject: Int? = null,
    val ffdPaymentMethod: Int? = null,
    val ffdVatTag: Int? = null,
    val ffdMeasureCode: Int? = null,
)

data class NomenclatureResponse(
    val id: String,
    val code: String,
    val name: String,
    val category: String,
    val priceCents: Long,
    val refundPolicyId: String?,
    val refundPolicyName: String?,
    val isActive: Boolean,
    val description: String?,
    val createdAt: Instant,
    val saleMode: String,
    val pricingMode: String,
    val routePercent: BigDecimal?,
    val minPriceCents: Long?,
    val maxPriceCents: Long?,
    val maxQtyPerTicket: Int,
    val refundAllowed: Boolean,
    val printName: String,
    val ffdPaymentObject: Int,
    val ffdPaymentMethod: Int,
    val ffdVatTag: Int,
    val ffdMeasureCode: Int,
)

data class UpsertTariffProfileRequest(
    @field:NotBlank val name: String,
    val routeId: UUID? = null,
    val validFrom: LocalDate? = null,
    val validTo: LocalDate? = null,
    val refundPolicyId: UUID? = null,
    val isActive: Boolean? = null,
)

data class TariffProfileResponse(
    val id: String,
    val name: String,
    val routeId: String?,
    val validFrom: LocalDate?,
    val validTo: LocalDate?,
    val refundPolicyId: String?,
    val refundPolicyName: String?,
    val isActive: Boolean,
    val stopCount: Int,
    val createdAt: Instant,
)

data class ReplaceTariffStopsRequest(
    val pointIds: List<UUID> = emptyList(),
)

data class UpsertTariffMatrixRequest(
    val cells: List<TariffCellRequest> = emptyList(),
)

data class TariffCellRequest(
    val fromStopOrder: Int,
    val toStopOrder: Int,
    @field:Min(0) val priceCents: Long,
    val isMirrorOverride: Boolean? = null,
)

data class TariffMatrixResponse(
    val profile: TariffProfileResponse,
    val stops: List<TariffProfileStopResponse>,
    val cells: List<TariffCellResponse>,
)

data class TariffProfileStopResponse(
    val id: String,
    val pointId: String,
    val stopOrder: Int,
    val pointCode: String?,
    val pointName: String?,
    val pointCity: String?,
)

data class TariffCellResponse(
    val id: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val priceCents: Long,
    val isMirrorOverride: Boolean,
)
