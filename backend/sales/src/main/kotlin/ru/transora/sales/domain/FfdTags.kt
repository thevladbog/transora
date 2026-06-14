package ru.transora.sales.domain

enum class FfdPaymentObject(val code: Int) {
    COMMODITY(1),
    SERVICE(4),
    PAYMENT(10),
    AGENT_COMMISSION(12),
    ;

    companion object {
        fun fromCode(code: Int): FfdPaymentObject =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown FFD payment object: $code")
    }
}

enum class FfdPaymentMethod(val code: Int) {
    FULL_PREPAYMENT(1),
    PARTIAL_PREPAYMENT(2),
    ADVANCE(3),
    FULL_PAYMENT(4),
    PARTIAL_PAYMENT_AND_CREDIT(5),
    CREDIT_TRANSFER(6),
    CREDIT_PAYMENT(7),
    ;

    companion object {
        fun fromCode(code: Int): FfdPaymentMethod =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown FFD payment method: $code")
    }
}

/** FFD tag 1199 — VAT rate (actual as of 2026, incl. 22% / 22/122). */
enum class FfdVatTag(val tag: Int, val labelRu: String) {
    VAT_20(1, "НДС 20%"),
    VAT_10(2, "НДС 10%"),
    VAT_20_120(3, "НДС 20/120"),
    VAT_10_110(4, "НДС 10/110"),
    VAT_0(5, "НДС 0%"),
    VAT_NONE(6, "Не облагается"),
    VAT_5(7, "НДС 5%"),
    VAT_7(8, "НДС 7%"),
    VAT_5_105(9, "НДС 5/105"),
    VAT_7_107(10, "НДС 7/107"),
    VAT_22(11, "НДС 22%"),
    VAT_22_122(12, "НДС 22/122"),
    ;

    companion object {
        fun fromTag(tag: Int): FfdVatTag =
            entries.firstOrNull { it.tag == tag }
                ?: throw IllegalArgumentException("Unknown FFD VAT tag: $tag")
    }
}

enum class NomenclatureSaleMode {
    STANDALONE,
    TICKET_ATTACHED,
}

enum class NomenclaturePricingMode {
    FIXED,
    PERCENT_OF_ROUTE,
}

enum class OrderNomenclatureLineStatus {
    ACTIVE,
    PARTIALLY_REFUNDED,
    REFUNDED,
}
