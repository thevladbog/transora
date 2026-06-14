package ru.transora.app.admin

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.CommercePolicyType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class NomenclatureAdminService(
    private val nomenclatureAdminRepository: NomenclatureAdminRepository,
    private val commercePolicyAdminRepository: CommercePolicyAdminRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<NomenclatureResponse> = nomenclatureAdminRepository.list().map { it.toResponse() }

    fun get(id: UUID): NomenclatureResponse =
        nomenclatureAdminRepository.findById(id)?.toResponse()
            ?: throw NoSuchElementException("Nomenclature item $id was not found")

    @Transactional
    fun create(request: UpsertNomenclatureRequest): NomenclatureResponse {
        val normalized = normalizeNomenclatureRequest(request)
        validateNomenclatureUpsert(normalized)
        validateRefundPolicy(normalized.refundPolicyId)
        val row = NomenclatureRow(
            id = UUID.randomUUID(),
            code = normalized.code.trim(),
            name = normalized.name.trim(),
            category = normalized.category.trim().uppercase(),
            priceCents = normalized.priceCents,
            refundPolicyId = normalized.refundPolicyId,
            refundPolicyName = null,
            isActive = normalized.isActive ?: true,
            description = normalized.description?.trim(),
            createdAt = Clock.systemUTC().instant(),
            saleMode = (normalized.saleMode ?: "STANDALONE").trim().uppercase(),
            pricingMode = (normalized.pricingMode ?: "FIXED").trim().uppercase(),
            routePercent = normalized.routePercent,
            minPriceCents = normalized.minPriceCents,
            maxPriceCents = normalized.maxPriceCents,
            maxQtyPerTicket = normalized.maxQtyPerTicket ?: 1,
            refundAllowed = normalized.refundAllowed ?: false,
            printName = normalized.printName!!.trim(),
            ffdPaymentObject = normalized.ffdPaymentObject ?: 4,
            ffdPaymentMethod = normalized.ffdPaymentMethod ?: 4,
            ffdVatTag = normalized.ffdVatTag ?: 6,
            ffdMeasureCode = normalized.ffdMeasureCode ?: 0,
        )
        nomenclatureAdminRepository.insert(row)
        auditLogService.record(module = "admin", action = "nomenclature.created", entityType = "nomenclature", entityId = row.id.toString())
        return nomenclatureAdminRepository.findById(row.id)!!.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpsertNomenclatureRequest): NomenclatureResponse {
        val existing = nomenclatureAdminRepository.findById(id)
            ?: throw NoSuchElementException("Nomenclature item $id was not found")
        val normalized = normalizeNomenclatureRequest(request)
        validateNomenclatureUpsert(normalized)
        validateRefundPolicy(normalized.refundPolicyId)
        val updated = existing.copy(
            code = normalized.code.trim(),
            name = normalized.name.trim(),
            category = normalized.category.trim().uppercase(),
            priceCents = normalized.priceCents,
            refundPolicyId = normalized.refundPolicyId,
            isActive = normalized.isActive ?: existing.isActive,
            description = normalized.description?.trim(),
            saleMode = (normalized.saleMode ?: "STANDALONE").trim().uppercase(),
            pricingMode = (normalized.pricingMode ?: "FIXED").trim().uppercase(),
            routePercent = normalized.routePercent,
            minPriceCents = normalized.minPriceCents,
            maxPriceCents = normalized.maxPriceCents,
            maxQtyPerTicket = normalized.maxQtyPerTicket ?: 1,
            refundAllowed = normalized.refundAllowed ?: false,
            printName = normalized.printName!!.trim(),
            ffdPaymentObject = normalized.ffdPaymentObject ?: 4,
            ffdPaymentMethod = normalized.ffdPaymentMethod ?: 4,
            ffdVatTag = normalized.ffdVatTag ?: 6,
            ffdMeasureCode = normalized.ffdMeasureCode ?: 0,
        )
        nomenclatureAdminRepository.update(updated)
        auditLogService.record(module = "admin", action = "nomenclature.updated", entityType = "nomenclature", entityId = id.toString())
        return nomenclatureAdminRepository.findById(id)!!.toResponse()
    }

    @Transactional
    fun delete(id: UUID) {
        if (nomenclatureAdminRepository.delete(id) == 0) {
            throw NoSuchElementException("Nomenclature item $id was not found")
        }
        auditLogService.record(module = "admin", action = "nomenclature.deleted", entityType = "nomenclature", entityId = id.toString())
    }

    private fun validateRefundPolicy(policyId: UUID?) {
        if (policyId == null) return
        val policy = commercePolicyAdminRepository.findPolicy(policyId)
            ?: throw DomainRuleViolation("Refund policy $policyId was not found")
        if (policy.policyType != CommercePolicyType.REFUND.name) {
            throw DomainRuleViolation("Only REFUND policies can be assigned to nomenclature refund")
        }
    }

    private fun normalizeNomenclatureRequest(request: UpsertNomenclatureRequest): UpsertNomenclatureRequest {
        val printName = request.printName?.trim()?.takeIf { it.isNotEmpty() } ?: request.name.trim()
        val refundAllowed = request.refundAllowed ?: false
        val refundPolicyId = if (refundAllowed) request.refundPolicyId else null
        return request.copy(
            printName = printName,
            refundPolicyId = refundPolicyId,
            refundAllowed = refundAllowed,
            saleMode = request.saleMode ?: "STANDALONE",
            pricingMode = request.pricingMode ?: "FIXED",
            maxQtyPerTicket = request.maxQtyPerTicket ?: 1,
            ffdPaymentObject = request.ffdPaymentObject ?: 4,
            ffdPaymentMethod = request.ffdPaymentMethod ?: 4,
            ffdVatTag = request.ffdVatTag ?: 6,
            ffdMeasureCode = request.ffdMeasureCode ?: 0,
        )
    }
}

@Service
class TariffProfileAdminService(
    private val tariffProfileAdminRepository: TariffProfileAdminRepository,
    private val pointAdminRepository: PointAdminRepository,
    private val commercePolicyAdminRepository: CommercePolicyAdminRepository,
    private val auditLogService: AuditLogService,
) {
    fun list(): List<TariffProfileResponse> = tariffProfileAdminRepository.list().map { it.toResponse() }

    fun get(id: UUID): TariffProfileResponse =
        tariffProfileAdminRepository.findById(id)?.toResponse()
            ?: throw NoSuchElementException("Tariff profile $id was not found")

    fun getMatrix(id: UUID): TariffMatrixResponse {
        val profile = get(id)
        val stops = tariffProfileAdminRepository.listStops(id).map { it.toResponse() }
        val cells = tariffProfileAdminRepository.listCells(id).map { it.toResponse() }
        return TariffMatrixResponse(profile = profile, stops = stops, cells = cells)
    }

    @Transactional
    fun create(request: UpsertTariffProfileRequest): TariffProfileResponse {
        validateRefundPolicy(request.refundPolicyId)
        val row = TariffProfileRow(
            id = UUID.randomUUID(),
            name = request.name.trim(),
            routeId = request.routeId,
            validFrom = request.validFrom,
            validTo = request.validTo,
            refundPolicyId = request.refundPolicyId,
            refundPolicyName = null,
            isActive = request.isActive ?: true,
            createdAt = Clock.systemUTC().instant(),
        )
        tariffProfileAdminRepository.insert(row)
        auditLogService.record(module = "admin", action = "tariff_profile.created", entityType = "tariff_profile", entityId = row.id.toString())
        return tariffProfileAdminRepository.findById(row.id)!!.toResponse()
    }

    @Transactional
    fun update(id: UUID, request: UpsertTariffProfileRequest): TariffProfileResponse {
        val existing = tariffProfileAdminRepository.findById(id)
            ?: throw NoSuchElementException("Tariff profile $id was not found")
        validateRefundPolicy(request.refundPolicyId)
        val updated = existing.copy(
            name = request.name.trim(),
            routeId = request.routeId,
            validFrom = request.validFrom,
            validTo = request.validTo,
            refundPolicyId = request.refundPolicyId,
            isActive = request.isActive ?: existing.isActive,
        )
        tariffProfileAdminRepository.update(updated)
        auditLogService.record(module = "admin", action = "tariff_profile.updated", entityType = "tariff_profile", entityId = id.toString())
        return tariffProfileAdminRepository.findById(id)!!.toResponse()
    }

    @Transactional
    fun delete(id: UUID) {
        if (tariffProfileAdminRepository.delete(id) == 0) {
            throw NoSuchElementException("Tariff profile $id was not found")
        }
        auditLogService.record(module = "admin", action = "tariff_profile.deleted", entityType = "tariff_profile", entityId = id.toString())
    }

    @Transactional
    fun replaceStops(id: UUID, pointIds: List<UUID>): TariffMatrixResponse {
        if (tariffProfileAdminRepository.findById(id) == null) {
            throw NoSuchElementException("Tariff profile $id was not found")
        }
        if (pointIds.isEmpty()) {
            throw DomainRuleViolation("At least one stop is required")
        }
        val distinct = pointIds.distinct()
        if (distinct.size != pointIds.size) {
            throw DomainRuleViolation("Duplicate points are not allowed")
        }
        distinct.forEach { pointId ->
            if (pointAdminRepository.findById(pointId) == null) {
                throw DomainRuleViolation("Point $pointId was not found")
            }
        }
        val stops = pointIds.mapIndexed { index, pointId ->
            TariffProfileStopRow(
                id = UUID.randomUUID(),
                profileId = id,
                pointId = pointId,
                stopOrder = index + 1,
            )
        }
        tariffProfileAdminRepository.replaceStops(id, stops)
        auditLogService.record(module = "admin", action = "tariff_profile.stops_updated", entityType = "tariff_profile", entityId = id.toString())
        return getMatrix(id)
    }

    @Transactional
    fun upsertMatrix(id: UUID, request: UpsertTariffMatrixRequest): TariffMatrixResponse {
        if (tariffProfileAdminRepository.findById(id) == null) {
            throw NoSuchElementException("Tariff profile $id was not found")
        }
        val stopCount = tariffProfileAdminRepository.listStops(id).size
        if (stopCount < 2) {
            throw DomainRuleViolation("At least two stops are required for tariff matrix")
        }
        val expanded = expandMirrorCells(request.cells)
        expanded.forEach { cell ->
            if (cell.fromStopOrder < 1 || cell.toStopOrder > stopCount || cell.toStopOrder <= cell.fromStopOrder) {
                throw DomainRuleViolation("Invalid stop order in tariff cell")
            }
        }
        val rows = expanded.map { cell ->
            TariffCellRow(
                id = UUID.randomUUID(),
                profileId = id,
                fromStopOrder = cell.fromStopOrder,
                toStopOrder = cell.toStopOrder,
                priceCents = cell.priceCents,
                isMirrorOverride = cell.isMirrorOverride ?: false,
            )
        }
        tariffProfileAdminRepository.upsertCells(id, rows)
        auditLogService.record(module = "admin", action = "tariff_profile.matrix_updated", entityType = "tariff_profile", entityId = id.toString())
        return getMatrix(id)
    }

    private fun expandMirrorCells(cells: List<TariffCellRequest>): List<TariffCellRequest> {
        val byKey = linkedMapOf<Pair<Int, Int>, TariffCellRequest>()
        cells.forEach { cell ->
            if (cell.toStopOrder <= cell.fromStopOrder) {
                return@forEach
            }
            val canonical = if (cell.fromStopOrder < cell.toStopOrder) {
                cell
            } else {
                cell.copy(fromStopOrder = cell.toStopOrder, toStopOrder = cell.fromStopOrder)
            }
            byKey[canonical.fromStopOrder to canonical.toStopOrder] = canonical
        }
        return byKey.values.toList()
    }

    private fun validateRefundPolicy(policyId: UUID?) {
        if (policyId == null) return
        val policy = commercePolicyAdminRepository.findPolicy(policyId)
            ?: throw DomainRuleViolation("Refund policy $policyId was not found")
        if (policy.policyType != CommercePolicyType.REFUND.name) {
            throw DomainRuleViolation("Only REFUND policies can be assigned to nomenclature refund")
        }
    }
}

private fun NomenclatureRow.toResponse() = NomenclatureResponse(
    id = id.toString(),
    code = code,
    name = name,
    category = category,
    priceCents = priceCents,
    refundPolicyId = refundPolicyId?.toString(),
    refundPolicyName = refundPolicyName,
    isActive = isActive,
    description = description,
    createdAt = createdAt,
    saleMode = saleMode,
    pricingMode = pricingMode,
    routePercent = routePercent,
    minPriceCents = minPriceCents,
    maxPriceCents = maxPriceCents,
    maxQtyPerTicket = maxQtyPerTicket,
    refundAllowed = refundAllowed,
    printName = printName,
    ffdPaymentObject = ffdPaymentObject,
    ffdPaymentMethod = ffdPaymentMethod,
    ffdVatTag = ffdVatTag,
    ffdMeasureCode = ffdMeasureCode,
)

private fun TariffProfileRow.toResponse() = TariffProfileResponse(
    id = id.toString(),
    name = name,
    routeId = routeId?.toString(),
    validFrom = validFrom,
    validTo = validTo,
    refundPolicyId = refundPolicyId?.toString(),
    refundPolicyName = refundPolicyName,
    isActive = isActive,
    stopCount = stopCount,
    createdAt = createdAt,
)

private fun TariffProfileStopRow.toResponse() = TariffProfileStopResponse(
    id = id.toString(),
    pointId = pointId.toString(),
    stopOrder = stopOrder,
    pointCode = pointCode,
    pointName = pointName,
    pointCity = pointCity,
)

private fun TariffCellRow.toResponse() = TariffCellResponse(
    id = id.toString(),
    fromStopOrder = fromStopOrder,
    toStopOrder = toStopOrder,
    priceCents = priceCents,
    isMirrorOverride = isMirrorOverride,
)
