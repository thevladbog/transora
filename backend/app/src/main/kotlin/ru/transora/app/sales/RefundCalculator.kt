package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.scheduling.TripRepository
import ru.transora.sales.domain.RefundPolicyTier
import ru.transora.sales.domain.RefundPreview
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class RefundCalculator(
    private val refundRepository: RefundRepository,
    private val tripRepository: TripRepository,
) {
    fun preview(ticketPriceCents: Long, tripId: UUID, refundAt: Instant = Clock.systemUTC().instant()): RefundPreview {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val policyId = RefundRepository.DEFAULT_POLICY_ID
        val tiers = refundRepository.findTiersByPolicyId(policyId)
        val serviceFeeCents = refundRepository.serviceFeeCents(policyId)
        val hoursUntilDeparture = Duration.between(refundAt, trip.departureTime).toMinutes() / 60.0
        val tier = resolveTier(tiers, hoursUntilDeparture)
            ?: throw DomainRuleViolation("No refund tier applies for this departure time")

        if (!tier.refundAllowed) {
            return RefundPreview(
                penaltyPercent = tier.penaltyPercent,
                penaltyCents = ticketPriceCents,
                serviceFeeCents = serviceFeeCents,
                refundCents = 0,
                refundAllowed = false,
            )
        }

        val penaltyCents = calculatePenaltyCents(ticketPriceCents, tier.penaltyPercent)
        val refundCents = (ticketPriceCents - penaltyCents - serviceFeeCents).coerceAtLeast(0)

        return RefundPreview(
            penaltyPercent = tier.penaltyPercent,
            penaltyCents = penaltyCents,
            serviceFeeCents = serviceFeeCents,
            refundCents = refundCents,
            refundAllowed = true,
        )
    }

    private fun resolveTier(tiers: List<RefundPolicyTier>, hoursUntilDeparture: Double): RefundPolicyTier? =
        tiers.firstOrNull { tier ->
            val minOk = tier.hoursBeforeMin?.let { hoursUntilDeparture >= it } ?: (hoursUntilDeparture < 0)
            val maxOk = tier.hoursBeforeMax?.let { hoursUntilDeparture < it } ?: true
            minOk && maxOk
        }

    private fun calculatePenaltyCents(priceCents: Long, penaltyPercent: BigDecimal): Long =
        BigDecimal(priceCents)
            .multiply(penaltyPercent)
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            .longValueExact()
}
