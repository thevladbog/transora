package ru.transora.app.admin

import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.FfdPaymentMethod
import ru.transora.sales.domain.FfdPaymentObject
import ru.transora.sales.domain.FfdVatTag
import ru.transora.sales.domain.NomenclaturePricingMode
import ru.transora.sales.domain.NomenclatureSaleMode
import java.math.BigDecimal

fun validateNomenclatureUpsert(request: UpsertNomenclatureRequest) {
    val printName = request.printName?.trim().orEmpty()
    if (printName.isBlank()) {
        throw DomainRuleViolation("print_name is required")
    }
    val effective = request.copy(
        printName = printName,
        saleMode = request.saleMode ?: "STANDALONE",
        pricingMode = request.pricingMode ?: "FIXED",
        maxQtyPerTicket = request.maxQtyPerTicket ?: 1,
        refundAllowed = request.refundAllowed ?: false,
        ffdPaymentObject = request.ffdPaymentObject ?: 4,
        ffdPaymentMethod = request.ffdPaymentMethod ?: 4,
        ffdVatTag = request.ffdVatTag ?: 6,
        ffdMeasureCode = request.ffdMeasureCode ?: 0,
    )

    val saleMode = parseSaleMode(requireNotNull(effective.saleMode))
    val pricingMode = parsePricingMode(requireNotNull(effective.pricingMode))

    validateFfdFields(effective)

    when (saleMode) {
        NomenclatureSaleMode.STANDALONE -> {
            if (pricingMode != NomenclaturePricingMode.FIXED) {
                throw DomainRuleViolation("Standalone nomenclature supports fixed pricing only")
            }
        }
        NomenclatureSaleMode.TICKET_ATTACHED -> {
            if (requireNotNull(effective.maxQtyPerTicket) < 1) {
                throw DomainRuleViolation("max_qty_per_ticket must be at least 1")
            }
        }
    }

    when (pricingMode) {
        NomenclaturePricingMode.FIXED -> {
            if (effective.priceCents < 0) {
                throw DomainRuleViolation("price_cents must be non-negative")
            }
        }
        NomenclaturePricingMode.PERCENT_OF_ROUTE -> {
            if (saleMode != NomenclatureSaleMode.TICKET_ATTACHED) {
                throw DomainRuleViolation("Percent pricing is allowed only for ticket-attached nomenclature")
            }
            val percent = effective.routePercent
                ?: throw DomainRuleViolation("route_percent is required for percent pricing")
            if (percent <= BigDecimal.ZERO || percent > BigDecimal(100)) {
                throw DomainRuleViolation("route_percent must be between 0 and 100")
            }
        }
    }

    val minPrice = effective.minPriceCents
    val maxPrice = effective.maxPriceCents
    if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
        throw DomainRuleViolation("min_price_cents cannot exceed max_price_cents")
    }

    if (effective.refundAllowed == true) {
        if (effective.refundPolicyId == null) {
            throw DomainRuleViolation("refund_policy_id is required when refund is allowed")
        }
    }
}

private fun validateFfdFields(request: UpsertNomenclatureRequest) {
    val printName = request.printName?.trim().orEmpty()
    if (printName.isBlank()) {
        throw DomainRuleViolation("print_name is required")
    }
    if (printName.length > 128) {
        throw DomainRuleViolation("print_name must be at most 128 characters")
    }
    runCatching { FfdPaymentObject.fromCode(requireNotNull(request.ffdPaymentObject)) }
        .getOrElse { throw DomainRuleViolation("Invalid ffd_payment_object") }
    runCatching { FfdPaymentMethod.fromCode(requireNotNull(request.ffdPaymentMethod)) }
        .getOrElse { throw DomainRuleViolation("Invalid ffd_payment_method") }
    runCatching { FfdVatTag.fromTag(requireNotNull(request.ffdVatTag)) }
        .getOrElse { throw DomainRuleViolation("Invalid ffd_vat_tag") }
}

private fun parseSaleMode(value: String): NomenclatureSaleMode =
    runCatching { NomenclatureSaleMode.valueOf(value.trim().uppercase()) }
        .getOrElse { throw DomainRuleViolation("Invalid sale_mode: $value") }

private fun parsePricingMode(value: String): NomenclaturePricingMode =
    runCatching { NomenclaturePricingMode.valueOf(value.trim().uppercase()) }
        .getOrElse { throw DomainRuleViolation("Invalid pricing_mode: $value") }
