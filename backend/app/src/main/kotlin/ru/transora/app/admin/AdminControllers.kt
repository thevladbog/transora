package ru.transora.app.admin

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
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
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Reporting and configuration")
class AdminReportController(
    private val adminReportService: AdminReportService,
    private val adminAuditService: AdminAuditService,
) {
    @GetMapping("/reports/station-revenue")
    @RequirePermission(Permissions.REPORTS_VIEW_STATION)
    @Operation(summary = "Station revenue report")
    fun stationRevenue(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): StationRevenueResponse = adminReportService.stationRevenue(from, to).toResponse()

    @GetMapping("/reports/passenger-flow")
    @RequirePermission(Permissions.REPORTS_VIEW_STATION)
    @Operation(summary = "Passenger flow report")
    fun passengerFlow(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): PassengerFlowResponse = adminReportService.passengerFlow(from, to).toResponse()

    @GetMapping("/audit")
    @RequirePermission(Permissions.USERS_VIEW)
    @Operation(summary = "Read admin and auth audit logs")
    fun audit(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(defaultValue = "100") @Min(1) limit: Int,
    ): AdminAuditResponse = adminAuditService.list(from, to, limit)
}

@RestController
@RequestMapping("/api/admin/tariffs")
@Tag(name = "Admin Tariffs")
class TariffAdminController(
    private val tariffAdminService: TariffAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun list(): List<TariffResponse> = tariffAdminService.list()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun get(@PathVariable id: UUID): TariffResponse = tariffAdminService.get(id)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun create(@Valid @RequestBody request: UpsertTariffRequest): TariffResponse =
        tariffAdminService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpsertTariffRequest): TariffResponse =
        tariffAdminService.update(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun delete(@PathVariable id: UUID) = tariffAdminService.delete(id)
}

@RestController
@RequestMapping("/api/admin/refund-policies")
@Tag(name = "Admin Refund Policies")
class RefundPolicyAdminController(
    private val refundPolicyAdminService: RefundPolicyAdminService,
) {
    @GetMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun list(): List<RefundPolicyResponse> = refundPolicyAdminService.list()

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun get(@PathVariable id: UUID): RefundPolicyResponse = refundPolicyAdminService.get(id)

    @PostMapping
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun create(@Valid @RequestBody request: UpsertRefundPolicyRequest): RefundPolicyResponse =
        refundPolicyAdminService.create(request)

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpsertRefundPolicyRequest): RefundPolicyResponse =
        refundPolicyAdminService.update(id, request)

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SETTINGS_MANAGE_TARIFFS)
    fun delete(@PathVariable id: UUID) = refundPolicyAdminService.delete(id)
}

data class StationRevenueResponse(
    val stationId: String?,
    val from: Instant,
    val to: Instant,
    val ticketsSold: Int,
    val grossRevenueCents: Long,
    val refundsCount: Int,
    val refundsCents: Long,
    val netRevenueCents: Long,
)

data class PassengerFlowResponse(
    val stationId: String?,
    val from: Instant,
    val to: Instant,
    val tripsCount: Int,
    val passengersIssued: Int,
    val passengersBoarded: Int,
    val passengersRefunded: Int,
)

data class UpsertTariffRequest(
    @field:NotBlank val routeNumber: String,
    @field:Min(1) val fromStopOrder: Int,
    @field:Min(2) val toStopOrder: Int,
    @field:Min(0) val priceCents: Long,
    val isActive: Boolean? = null,
)

data class TariffResponse(
    val id: String,
    val routeNumber: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val priceCents: Long,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class UpsertRefundPolicyRequest(
    @field:NotBlank val name: String,
    val isActive: Boolean? = null,
    val serviceFeeCents: Long? = null,
    val tiers: List<RefundPolicyTierRequest> = emptyList(),
)

data class RefundPolicyTierRequest(
    val hoursBeforeMin: Int? = null,
    val hoursBeforeMax: Int? = null,
    val penaltyPercent: BigDecimal,
    val refundAllowed: Boolean? = null,
    val sortOrder: Int? = null,
)

data class RefundPolicyResponse(
    val id: String,
    val name: String,
    val isActive: Boolean,
    val serviceFeeCents: Long,
    val createdAt: Instant,
    val tiers: List<RefundPolicyTierResponse>,
)

data class RefundPolicyTierResponse(
    val id: String,
    val hoursBeforeMin: Int?,
    val hoursBeforeMax: Int?,
    val penaltyPercent: BigDecimal,
    val refundAllowed: Boolean,
    val sortOrder: Int,
)

data class AdminAuditResponse(
    val adminEntries: List<AuditEntryResponse>,
    val authEntries: List<AuthAuditEntryResponse>,
)

data class AuditEntryResponse(
    val id: String,
    val actorId: String?,
    val stationId: String?,
    val module: String,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val detailsJson: String?,
    val createdAt: Instant,
)

data class AuthAuditEntryResponse(
    val id: String,
    val userId: String?,
    val eventType: String,
    val detailsJson: String?,
    val createdAt: Instant,
)
