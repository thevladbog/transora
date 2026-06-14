package ru.transora.app.sales

import org.springframework.stereotype.Component
import ru.transora.app.admin.NomenclatureAdminRepository
import ru.transora.sales.domain.PolicyPercentBasis
import ru.transora.sales.domain.PolicyPricingMode
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class PolicyPriceCalculator(
    private val nomenclatureAdminRepository: NomenclatureAdminRepository,
    private val nomenclaturePriceCalculator: NomenclaturePriceCalculator,
) {
    fun unitPriceCents(
        policy: CommercePolicyRow,
        tier: CommercePolicyTierRow?,
        routePriceCents: Long,
        refundAmountCents: Long = 0,
    ): Long {
        val tierFixed = tier?.fixedPriceCents
        val tierPercent = tier?.percentValue
        return when (policy.pricingMode) {
            PolicyPricingMode.FIXED -> tierFixed ?: policy.fixedPriceCents ?: 0L
            PolicyPricingMode.PERCENT -> {
                val percent = tierPercent ?: policy.percentValue
                    ?: throw IllegalArgumentException("percent_value is required for PERCENT pricing")
                val basis = resolveBasis(policy, refundAmountCents, routePriceCents)
                var price = BigDecimal(basis)
                    .multiply(percent)
                    .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
                    .longValueExact()
                policy.minPriceCents?.let { min -> price = maxOf(price, min) }
                policy.maxPriceCents?.let { max -> price = minOf(price, max) }
                price
            }
            PolicyPricingMode.FROM_NOMENCLATURE -> {
                val itemId = policy.nomenclatureItemId
                    ?: throw IllegalArgumentException("nomenclature_item_id is required for FROM_NOMENCLATURE pricing")
                val item = nomenclatureAdminRepository.findById(itemId)
                    ?: throw NoSuchElementException("Nomenclature item $itemId was not found")
                nomenclaturePriceCalculator.unitPriceCents(item, routePriceCents)
            }
        }
    }

    private fun resolveBasis(policy: CommercePolicyRow, refundAmountCents: Long, routePriceCents: Long): Long =
        when (policy.percentBasis?.let { PolicyPercentBasis.valueOf(it) }) {
            PolicyPercentBasis.REFUND_AMOUNT -> refundAmountCents
            PolicyPercentBasis.ROUTE_PRICE, null -> routePriceCents
        }
}
