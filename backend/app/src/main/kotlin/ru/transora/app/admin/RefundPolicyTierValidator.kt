package ru.transora.app.admin

import ru.transora.app.domain.DomainRuleViolation
import java.math.BigDecimal

object RefundPolicyTierValidator {
    fun validateIntervalTiers(tiers: List<RefundPolicyTierRequest>) {
        if (tiers.isEmpty()) return
        validateOverlap(tiers)
    }

    private fun validateOverlap(tiers: List<RefundPolicyTierRequest>) {
        val sorted = tiers.withIndex().sortedWith(
            compareBy<IndexedValue<RefundPolicyTierRequest>> { it.value.sortOrder ?: (it.index + 1) }
                .thenBy { it.index },
        )
        val intervals = sorted.map { (_, tier) ->
            val min = tier.hoursBeforeMin?.toDouble() ?: Double.NEGATIVE_INFINITY
            val max = tier.hoursBeforeMax?.toDouble() ?: Double.POSITIVE_INFINITY
            if (max <= min && tier.hoursBeforeMax != null && tier.hoursBeforeMin != null) {
                throw DomainRuleViolation("Threshold hoursBeforeMax must be greater than hoursBeforeMin")
            }
            min to max
        }
        for (i in intervals.indices) {
            for (j in i + 1 until intervals.size) {
                if (overlaps(intervals[i], intervals[j])) {
                    throw DomainRuleViolation("Policy thresholds must not overlap")
                }
            }
        }
    }

    fun validate(tiers: List<RefundPolicyTierRequest>) {
        if (tiers.isEmpty()) {
            throw DomainRuleViolation("At least one refund threshold is required")
        }
        validateOverlap(tiers)
    }

    private fun overlaps(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
        val (aMin, aMax) = a
        val (bMin, bMax) = b
        return aMin < bMax && bMin < aMax
    }

    fun normalizeTiers(tiers: List<RefundPolicyTierRequest>): List<RefundPolicyTierRequest> =
        tiers.mapIndexed { index, tier ->
            tier.copy(
                sortOrder = tier.sortOrder ?: (index + 1),
                penaltyPercent = tier.penaltyPercent.coerceIn(BigDecimal.ZERO, BigDecimal(100)),
            )
        }
}
