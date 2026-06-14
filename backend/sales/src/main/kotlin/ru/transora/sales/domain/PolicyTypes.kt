package ru.transora.sales.domain

enum class CommercePolicyType {
    REFUND,
    SALE,
}

enum class PolicyPricingMode {
    FROM_NOMENCLATURE,
    FIXED,
    PERCENT,
}

enum class PolicyPercentBasis {
    ROUTE_PRICE,
    REFUND_AMOUNT,
}
