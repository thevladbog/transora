package ru.transora.app.admin

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.admin.domain.PassengerFlowReport
import ru.transora.admin.domain.StationRevenueReport
import ru.transora.iam.permissions.Permissions
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class AdminReportService(
    private val adminReportRepository: AdminReportRepository,
) {
    fun stationRevenue(from: Instant?, to: Instant?): StationRevenueReport {
        val range = resolveRange(from, to)
        val stationId = scopedStationId()
        return adminReportRepository.stationRevenue(stationId, range.first, range.second)
    }

    fun passengerFlow(from: Instant?, to: Instant?): PassengerFlowReport {
        val range = resolveRange(from, to)
        val stationId = scopedStationId()
        return adminReportRepository.passengerFlow(stationId, range.first, range.second)
    }

    private fun scopedStationId(): UUID? {
        val principal = currentPrincipal() ?: return null
        if (principal.isSuperuser || principal.permissions.contains(Permissions.REPORTS_VIEW_NETWORK)) {
            return null
        }
        return StationScope.currentStationId()
    }

    private fun resolveRange(from: Instant?, to: Instant?): Pair<Instant, Instant> {
        val end = to ?: Clock.systemUTC().instant()
        val start = from ?: end.minus(30, ChronoUnit.DAYS)
        return start to end
    }
}

@Service
class TariffAdminService(
    private val tariffAdminRepository: TariffAdminRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<TariffResponse> = tariffAdminRepository.list().map { it.toResponse() }

    fun get(id: UUID): TariffResponse =
        tariffAdminRepository.findById(id)?.toResponse()
            ?: throw NoSuchElementException("Tariff $id was not found")

    @Transactional
    fun create(request: UpsertTariffRequest): TariffResponse {
        val row = TariffRow(
            id = UUID.randomUUID(),
            routeNumber = request.routeNumber.trim(),
            fromStopOrder = request.fromStopOrder,
            toStopOrder = request.toStopOrder,
            priceCents = request.priceCents,
            isActive = request.isActive ?: true,
            createdAt = Clock.systemUTC().instant(),
        )
        tariffAdminRepository.insert(row)
        auditLogService.record(module = "admin", action = "tariff.created", entityType = "tariff", entityId = row.id.toString())
        return row.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpsertTariffRequest): TariffResponse {
        val existing = tariffAdminRepository.findById(id)
            ?: throw NoSuchElementException("Tariff $id was not found")
        val updated = existing.copy(
            routeNumber = request.routeNumber.trim(),
            fromStopOrder = request.fromStopOrder,
            toStopOrder = request.toStopOrder,
            priceCents = request.priceCents,
            isActive = request.isActive ?: existing.isActive,
        )
        tariffAdminRepository.update(updated)
        auditLogService.record(module = "admin", action = "tariff.updated", entityType = "tariff", entityId = id.toString())
        return updated.toResponse()
    }

    @Transactional
    fun delete(id: UUID) {
        if (tariffAdminRepository.delete(id) == 0) {
            throw NoSuchElementException("Tariff $id was not found")
        }
        auditLogService.record(module = "admin", action = "tariff.deleted", entityType = "tariff", entityId = id.toString())
    }
}

@Service
class RefundPolicyAdminService(
    private val refundPolicyAdminRepository: RefundPolicyAdminRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<RefundPolicyResponse> =
        refundPolicyAdminRepository.listPolicies().map { policy ->
            policy.toResponse(refundPolicyAdminRepository.listTiers(policy.id))
        }

    fun get(id: UUID): RefundPolicyResponse {
        val policy = refundPolicyAdminRepository.findPolicy(id)
            ?: throw NoSuchElementException("Refund policy $id was not found")
        return policy.toResponse(refundPolicyAdminRepository.listTiers(id))
    }

    @Transactional
    fun create(request: UpsertRefundPolicyRequest): RefundPolicyResponse {
        val id = UUID.randomUUID()
        val now = Clock.systemUTC().instant()
        val policy = RefundPolicyRow(
            id = id,
            name = request.name.trim(),
            isActive = request.isActive ?: true,
            serviceFeeCents = request.serviceFeeCents ?: 0,
            createdAt = now,
        )
        refundPolicyAdminRepository.insertPolicy(policy)
        val tiers = persistTiers(id, request.tiers)
        auditLogService.record(module = "admin", action = "refund_policy.created", entityType = "refund_policy", entityId = id.toString())
        return policy.toResponse(tiers)
    }

    @Transactional
    fun update(id: UUID, request: UpsertRefundPolicyRequest): RefundPolicyResponse {
        val existing = refundPolicyAdminRepository.findPolicy(id)
            ?: throw NoSuchElementException("Refund policy $id was not found")
        val updated = existing.copy(
            name = request.name.trim(),
            isActive = request.isActive ?: existing.isActive,
            serviceFeeCents = request.serviceFeeCents ?: existing.serviceFeeCents,
        )
        refundPolicyAdminRepository.updatePolicy(updated)
        val tiers = persistTiers(id, request.tiers)
        auditLogService.record(module = "admin", action = "refund_policy.updated", entityType = "refund_policy", entityId = id.toString())
        return updated.toResponse(tiers)
    }

    @Transactional
    fun delete(id: UUID) {
        if (refundPolicyAdminRepository.deletePolicy(id) == 0) {
            throw NoSuchElementException("Refund policy $id was not found")
        }
        auditLogService.record(module = "admin", action = "refund_policy.deleted", entityType = "refund_policy", entityId = id.toString())
    }

    private fun persistTiers(policyId: UUID, tiers: List<RefundPolicyTierRequest>): List<RefundPolicyTierRow> {
        val rows = tiers.mapIndexed { index, tier ->
            RefundPolicyTierRow(
                id = UUID.randomUUID(),
                policyId = policyId,
                hoursBeforeMin = tier.hoursBeforeMin,
                hoursBeforeMax = tier.hoursBeforeMax,
                penaltyPercent = tier.penaltyPercent,
                refundAllowed = tier.refundAllowed ?: true,
                sortOrder = tier.sortOrder ?: (index + 1).toShort().toInt(),
            )
        }
        refundPolicyAdminRepository.replaceTiers(policyId, rows)
        return rows
    }
}

@Service
class AdminAuditService(
    private val auditLogRepository: AuditLogRepository,
) {
    fun list(from: Instant?, to: Instant?, limit: Int): AdminAuditResponse {
        val principal = currentPrincipal()
        val stationId = if (principal?.isSuperuser == true ||
            principal?.permissions?.contains(Permissions.REPORTS_VIEW_NETWORK) == true
        ) {
            null
        } else {
            StationScope.currentStationId()
        }
        val entries = auditLogRepository.list(stationId, from, to, limit.coerceIn(1, 500))
            .map { it.toResponse() }
        val authEntries = auditLogRepository.listAuthAudit(stationId, limit.coerceIn(1, 500))
            .map { it.toResponse() }
        return AdminAuditResponse(
            adminEntries = entries,
            authEntries = authEntries,
        )
    }
}

private fun TariffRow.toResponse() = TariffResponse(
    id = id.toString(),
    routeNumber = routeNumber,
    fromStopOrder = fromStopOrder,
    toStopOrder = toStopOrder,
    priceCents = priceCents,
    isActive = isActive,
    createdAt = createdAt,
)

private fun RefundPolicyRow.toResponse(tiers: List<RefundPolicyTierRow>) = RefundPolicyResponse(
    id = id.toString(),
    name = name,
    isActive = isActive,
    serviceFeeCents = serviceFeeCents,
    createdAt = createdAt,
    tiers = tiers.map { it.toResponse() },
)

private fun RefundPolicyTierRow.toResponse() = RefundPolicyTierResponse(
    id = id.toString(),
    hoursBeforeMin = hoursBeforeMin,
    hoursBeforeMax = hoursBeforeMax,
    penaltyPercent = penaltyPercent,
    refundAllowed = refundAllowed,
    sortOrder = sortOrder,
)

private fun ru.transora.admin.domain.AuditLogEntry.toResponse() = AuditEntryResponse(
    id = id.toString(),
    actorId = actorId?.toString(),
    stationId = stationId?.toString(),
    module = module,
    action = action,
    entityType = entityType,
    entityId = entityId,
    detailsJson = detailsJson,
    createdAt = createdAt,
)

private fun AuthAuditRow.toResponse() = AuthAuditEntryResponse(
    id = id.toString(),
    userId = userId?.toString(),
    eventType = eventType,
    detailsJson = detailsJson,
    createdAt = createdAt,
)

fun StationRevenueReport.toResponse() = StationRevenueResponse(
    stationId = stationId?.toString(),
    from = from,
    to = to,
    ticketsSold = ticketsSold,
    grossRevenueCents = grossRevenueCents,
    refundsCount = refundsCount,
    refundsCents = refundsCents,
    netRevenueCents = netRevenueCents,
)

fun PassengerFlowReport.toResponse() = PassengerFlowResponse(
    stationId = stationId?.toString(),
    from = from,
    to = to,
    tripsCount = tripsCount,
    passengersIssued = passengersIssued,
    passengersBoarded = passengersBoarded,
    passengersRefunded = passengersRefunded,
)
