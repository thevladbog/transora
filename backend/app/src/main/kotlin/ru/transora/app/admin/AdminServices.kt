package ru.transora.app.admin

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.iam.security.StationScope
import ru.transora.app.iam.security.currentPrincipal
import ru.transora.admin.domain.PassengerFlowReport
import ru.transora.admin.domain.StationRevenueReport
import ru.transora.iam.permissions.Permissions
import ru.transora.sales.domain.CommercePolicyType
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
class CommercePolicyAdminService(
    private val commercePolicyAdminRepository: CommercePolicyAdminRepository,
    private val routeCommercePolicyRepository: RouteCommercePolicyRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(policyType: String? = null): List<CommercePolicyResponse> =
        commercePolicyAdminRepository.listPolicies(policyType).map { policy ->
            policy.toResponse(commercePolicyAdminRepository.listTiers(policy.id))
        }

    fun get(id: UUID): CommercePolicyResponse {
        val policy = commercePolicyAdminRepository.findPolicy(id)
            ?: throw NoSuchElementException("Policy $id was not found")
        return policy.toResponse(commercePolicyAdminRepository.listTiers(id))
    }

    @Transactional
    fun create(request: UpsertCommercePolicyRequest): CommercePolicyResponse {
        CommercePolicyValidator.validate(request)
        val id = UUID.randomUUID()
        val now = Clock.systemUTC().instant()
        val policy = request.toRow(id, now)
        commercePolicyAdminRepository.insertPolicy(policy)
        val tiers = persistTiers(id, request)
        auditLogService.record(module = "admin", action = "commerce_policy.created", entityType = "commerce_policy", entityId = id.toString())
        return policy.toResponse(tiers)
    }

    @Transactional
    fun update(id: UUID, request: UpsertCommercePolicyRequest): CommercePolicyResponse {
        val existing = commercePolicyAdminRepository.findPolicy(id)
            ?: throw NoSuchElementException("Policy $id was not found")
        CommercePolicyValidator.validate(request)
        val updated = existing.copy(
            name = request.name.trim(),
            isActive = request.isActive ?: existing.isActive,
            serviceFeeCents = request.serviceFeeCents ?: existing.serviceFeeCents,
            policyType = request.policyType?.trim()?.uppercase() ?: "REFUND",
            nomenclatureItemId = request.nomenclatureItemId,
            isMandatory = request.isMandatory ?: existing.isMandatory,
            pricingMode = request.pricingMode?.trim()?.uppercase() ?: "FROM_NOMENCLATURE",
            fixedPriceCents = request.fixedPriceCents,
            percentValue = request.percentValue,
            percentBasis = request.percentBasis?.trim()?.uppercase(),
            minPriceCents = request.minPriceCents,
            maxPriceCents = request.maxPriceCents,
        )
        commercePolicyAdminRepository.updatePolicy(updated)
        val tiers = persistTiers(id, request)
        auditLogService.record(module = "admin", action = "commerce_policy.updated", entityType = "commerce_policy", entityId = id.toString())
        return updated.toResponse(tiers)
    }

    @Transactional
    fun delete(id: UUID) {
        val nomenclatureRefs = commercePolicyAdminRepository.countNomenclatureReferences(id)
        val profileRefs = commercePolicyAdminRepository.countTariffProfileReferences(id)
        val routeRefs = commercePolicyAdminRepository.countRouteReferences(id)
        if (nomenclatureRefs > 0 || profileRefs > 0 || routeRefs > 0) {
            throw DomainRuleViolation(
                "Policy is referenced by $nomenclatureRefs nomenclature item(s), " +
                    "$profileRefs tariff profile(s), and $routeRefs route(s)",
            )
        }
        if (commercePolicyAdminRepository.deletePolicy(id) == 0) {
            throw NoSuchElementException("Policy $id was not found")
        }
        auditLogService.record(module = "admin", action = "commerce_policy.deleted", entityType = "commerce_policy", entityId = id.toString())
    }

    fun listRoutePolicies(routeId: UUID): RoutePoliciesResponse {
        val bindings = routeCommercePolicyRepository.listByRouteId(routeId)
        return RoutePoliciesResponse(
            policies = bindings.map {
                RoutePolicyEntry(
                    policyId = it.policyId.toString(),
                    priority = it.priority,
                    policyName = it.policyName,
                    policyType = it.policyType,
                    policyActive = it.policyActive,
                )
            },
        )
    }

    @Transactional
    fun replaceRoutePolicies(routeId: UUID, request: ReplaceRoutePoliciesRequest): RoutePoliciesResponse {
        val policyIds = request.policies.map { it.policyId }
        if (policyIds.size != policyIds.toSet().size) {
            throw DomainRuleViolation("Duplicate policies are not allowed on a route")
        }
        val priorities = request.policies.map { it.priority }
        if (priorities.size != priorities.toSet().size) {
            throw DomainRuleViolation("Duplicate priorities are not allowed on a route")
        }
        if (priorities.any { it < 1 }) {
            throw DomainRuleViolation("Priority must be >= 1")
        }
        val rows = policyIds.mapNotNull { commercePolicyAdminRepository.findPolicy(it) }
        if (rows.size != policyIds.size) {
            throw DomainRuleViolation("One or more policies were not found")
        }
        CommercePolicyValidator.validateRoutePolicies(rows)
        val ordered = request.policies.sortedBy { it.priority }.map { it.policyId }
        routeCommercePolicyRepository.replaceRoutePolicies(routeId, ordered)
        auditLogService.record(module = "admin", action = "route.policies_updated", entityType = "route", entityId = routeId.toString())
        return listRoutePolicies(routeId)
    }

    private fun persistTiers(policyId: UUID, request: UpsertCommercePolicyRequest): List<CommercePolicyTierRow> {
        val normalized = RefundPolicyTierValidator.normalizeTiers(request.tiers)
        if (request.policyType?.trim()?.uppercase() == CommercePolicyType.REFUND.name) {
            RefundPolicyTierValidator.validate(normalized)
        } else if (normalized.isNotEmpty()) {
            RefundPolicyTierValidator.validateIntervalTiers(normalized)
        }
        val rows = normalized.map { tier ->
            CommercePolicyTierRow(
                id = UUID.randomUUID(),
                policyId = policyId,
                hoursBeforeMin = tier.hoursBeforeMin,
                hoursBeforeMax = tier.hoursBeforeMax,
                penaltyPercent = tier.penaltyPercent,
                refundAllowed = tier.refundAllowed ?: true,
                sortOrder = tier.sortOrder ?: 1,
                fixedPriceCents = tier.fixedPriceCents,
                percentValue = tier.percentValue,
            )
        }
        commercePolicyAdminRepository.replaceTiers(policyId, rows)
        return rows
    }

    private fun UpsertCommercePolicyRequest.toRow(id: UUID, createdAt: Instant) = CommercePolicyRow(
        id = id,
        name = name.trim(),
        isActive = isActive ?: true,
        serviceFeeCents = serviceFeeCents ?: 0,
        createdAt = createdAt,
        policyType = policyType?.trim()?.uppercase() ?: "REFUND",
        nomenclatureItemId = nomenclatureItemId,
        isMandatory = isMandatory ?: false,
        pricingMode = pricingMode?.trim()?.uppercase() ?: "FROM_NOMENCLATURE",
        fixedPriceCents = fixedPriceCents,
        percentValue = percentValue,
        percentBasis = percentBasis?.trim()?.uppercase(),
        minPriceCents = minPriceCents,
        maxPriceCents = maxPriceCents,
    )
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

private fun CommercePolicyRow.toResponse(tiers: List<CommercePolicyTierRow>) = CommercePolicyResponse(
    id = id.toString(),
    name = name,
    isActive = isActive,
    serviceFeeCents = serviceFeeCents,
    createdAt = createdAt,
    policyType = policyType,
    nomenclatureItemId = nomenclatureItemId?.toString(),
    nomenclatureCode = nomenclatureCode,
    nomenclatureName = nomenclatureName,
    isMandatory = isMandatory,
    pricingMode = pricingMode,
    fixedPriceCents = fixedPriceCents,
    percentValue = percentValue,
    percentBasis = percentBasis,
    minPriceCents = minPriceCents,
    maxPriceCents = maxPriceCents,
    tiers = tiers.map { it.toResponse() },
)

private fun CommercePolicyTierRow.toResponse() = CommercePolicyTierResponse(
    id = id.toString(),
    hoursBeforeMin = hoursBeforeMin,
    hoursBeforeMax = hoursBeforeMax,
    penaltyPercent = penaltyPercent,
    refundAllowed = refundAllowed,
    sortOrder = sortOrder,
    fixedPriceCents = fixedPriceCents,
    percentValue = percentValue,
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
