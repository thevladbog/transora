package ru.transora.app.sales

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import ru.transora.sales.domain.CommercePolicyType
import ru.transora.sales.domain.PolicyPercentBasis
import ru.transora.sales.domain.PolicyPricingMode
import java.math.BigDecimal
import java.util.UUID

class PolicyPriceCalculatorTest : ru.transora.app.IntegrationTestBase() {
    @Autowired
    private lateinit var calculator: PolicyPriceCalculator

    @Test
    fun `fixed pricing uses header amount`() {
        val policy = basePolicy(
            pricingMode = PolicyPricingMode.FIXED,
            fixedPriceCents = 10000,
        )
        assertThat(calculator.unitPriceCents(policy, tier = null, routePriceCents = 50000)).isEqualTo(10000)
    }

    @Test
    fun `fixed pricing prefers tier override`() {
        val policy = basePolicy(
            pricingMode = PolicyPricingMode.FIXED,
            fixedPriceCents = 10000,
        )
        val tier = baseTier(fixedPriceCents = 7500)
        assertThat(calculator.unitPriceCents(policy, tier, routePriceCents = 50000)).isEqualTo(7500)
    }

    @Test
    fun `percent pricing clamps to bounds`() {
        val policy = basePolicy(
            pricingMode = PolicyPricingMode.PERCENT,
            percentValue = BigDecimal("10.00"),
            percentBasis = PolicyPercentBasis.ROUTE_PRICE.name,
            minPriceCents = 3000,
            maxPriceCents = 8000,
        )
        assertThat(calculator.unitPriceCents(policy, null, routePriceCents = 10000)).isEqualTo(3000)
        assertThat(calculator.unitPriceCents(policy, null, routePriceCents = 200000)).isEqualTo(8000)
    }

    @Test
    fun `percent refund amount basis uses refund cents`() {
        val policy = basePolicy(
            pricingMode = PolicyPricingMode.PERCENT,
            percentValue = BigDecimal("5.00"),
            percentBasis = PolicyPercentBasis.REFUND_AMOUNT.name,
        )
        assertThat(calculator.unitPriceCents(policy, null, routePriceCents = 100000, refundAmountCents = 40000))
            .isEqualTo(2000)
    }

    private fun basePolicy(
        pricingMode: PolicyPricingMode,
        fixedPriceCents: Long? = null,
        percentValue: BigDecimal? = null,
        percentBasis: String? = null,
        minPriceCents: Long? = null,
        maxPriceCents: Long? = null,
    ): CommercePolicyRow =
        CommercePolicyRow(
            id = UUID.randomUUID(),
            name = "Test",
            isActive = true,
            serviceFeeCents = 0,
            policyType = CommercePolicyType.REFUND,
            nomenclatureItemId = null,
            nomenclatureCode = null,
            nomenclatureName = null,
            isMandatory = false,
            pricingMode = pricingMode,
            fixedPriceCents = fixedPriceCents,
            percentValue = percentValue,
            percentBasis = percentBasis,
            minPriceCents = minPriceCents,
            maxPriceCents = maxPriceCents,
        )

    private fun baseTier(fixedPriceCents: Long? = null, percentValue: BigDecimal? = null): CommercePolicyTierRow =
        CommercePolicyTierRow(
            id = UUID.randomUUID(),
            policyId = UUID.randomUUID(),
            hoursBeforeMin = null,
            hoursBeforeMax = null,
            penaltyPercent = BigDecimal.ZERO,
            refundAllowed = true,
            sortOrder = 1,
            fixedPriceCents = fixedPriceCents,
            percentValue = percentValue,
        )
}
