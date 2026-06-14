package ru.transora.app.admin

import ru.transora.app.domain.DomainRuleViolation
import ru.transora.sales.domain.CommercePolicyType
import ru.transora.sales.domain.PolicyPricingMode
import java.math.BigDecimal

object CommercePolicyValidator {
    fun validate(request: UpsertCommercePolicyRequest) {
        val policyType = CommercePolicyType.valueOf(request.policyType?.trim()?.uppercase() ?: "REFUND")
        val pricingMode = PolicyPricingMode.valueOf(request.pricingMode?.trim()?.uppercase() ?: "FROM_NOMENCLATURE")

        when (policyType) {
            CommercePolicyType.SALE -> {
                if (request.nomenclatureItemId == null) {
                    throw DomainRuleViolation("nomenclature_item_id is required for SALE policies")
                }
            }
            CommercePolicyType.REFUND -> Unit
        }

        when (pricingMode) {
            PolicyPricingMode.FIXED -> {
                if (request.fixedPriceCents == null || request.fixedPriceCents < 0) {
                    throw DomainRuleViolation("fixed_price_cents is required and must be >= 0 for FIXED pricing")
                }
            }
            PolicyPricingMode.PERCENT -> {
                val percent = request.percentValue
                    ?: throw DomainRuleViolation("percent_value is required for PERCENT pricing")
                if (percent <= BigDecimal.ZERO || percent > BigDecimal(100)) {
                    throw DomainRuleViolation("percent_value must be in (0, 100]")
                }
            }
            PolicyPricingMode.FROM_NOMENCLATURE -> {
                if (policyType == CommercePolicyType.SALE && request.nomenclatureItemId == null) {
                    throw DomainRuleViolation("nomenclature_item_id is required for FROM_NOMENCLATURE SALE policies")
                }
            }
        }

        if (policyType == CommercePolicyType.REFUND) {
            RefundPolicyTierValidator.validate(request.tiers)
        } else if (request.tiers.isNotEmpty()) {
            RefundPolicyTierValidator.validateIntervalTiers(request.tiers)
        }
    }

    fun validateRoutePolicies(policyRows: List<CommercePolicyRow>) {
        val refundCount = policyRows.count { it.policyType == CommercePolicyType.REFUND.name }
        if (refundCount > 1) {
            throw DomainRuleViolation("Route may have at most one REFUND policy")
        }
    }
}
