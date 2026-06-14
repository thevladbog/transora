package ru.transora.app.sales

import org.springframework.stereotype.Component
import ru.transora.app.admin.NomenclatureRow
import ru.transora.sales.domain.NomenclaturePricingMode
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class NomenclaturePriceCalculator {
    fun unitPriceCents(item: NomenclatureRow, routePriceCents: Long): Long =
        when (NomenclaturePricingMode.valueOf(item.pricingMode)) {
            NomenclaturePricingMode.FIXED -> item.priceCents
            NomenclaturePricingMode.PERCENT_OF_ROUTE -> {
                val percent = item.routePercent
                    ?: throw IllegalArgumentException("route_percent is required for PERCENT_OF_ROUTE pricing")
                var price = BigDecimal(routePriceCents)
                    .multiply(percent)
                    .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
                    .longValueExact()
                item.minPriceCents?.let { min -> price = maxOf(price, min) }
                item.maxPriceCents?.let { max -> price = minOf(price, max) }
                price
            }
        }
}
