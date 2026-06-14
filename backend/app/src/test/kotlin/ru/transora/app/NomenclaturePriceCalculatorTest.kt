package ru.transora.app.sales

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.transora.app.admin.NomenclatureRow
import ru.transora.sales.domain.NomenclaturePricingMode
import ru.transora.sales.domain.NomenclatureSaleMode
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class NomenclaturePriceCalculatorTest {
    private val calculator = NomenclaturePriceCalculator()

    @Test
    fun `fixed pricing returns catalog price`() {
        val item = baseItem(pricingMode = NomenclaturePricingMode.FIXED.name, priceCents = 50000)
        assertThat(calculator.unitPriceCents(item, routePriceCents = 150000)).isEqualTo(50000)
    }

    @Test
    fun `percent pricing applies bounds`() {
        val item = baseItem(
            pricingMode = NomenclaturePricingMode.PERCENT_OF_ROUTE.name,
            routePercent = BigDecimal("10.00"),
            minPriceCents = 20000,
            maxPriceCents = 30000,
        )
        assertThat(calculator.unitPriceCents(item, routePriceCents = 100000)).isEqualTo(20000)
        assertThat(calculator.unitPriceCents(item, routePriceCents = 500000)).isEqualTo(30000)
    }

    private fun baseItem(
        pricingMode: String,
        priceCents: Long = 0,
        routePercent: BigDecimal? = null,
        minPriceCents: Long? = null,
        maxPriceCents: Long? = null,
    ): NomenclatureRow =
        NomenclatureRow(
            id = UUID.randomUUID(),
            code = "TEST",
            name = "Test",
            category = "SERVICE",
            priceCents = priceCents,
            refundPolicyId = null,
            refundPolicyName = null,
            isActive = true,
            description = null,
            createdAt = Instant.now(),
            saleMode = NomenclatureSaleMode.TICKET_ATTACHED.name,
            pricingMode = pricingMode,
            routePercent = routePercent,
            minPriceCents = minPriceCents,
            maxPriceCents = maxPriceCents,
            printName = "Test print",
        )
}
