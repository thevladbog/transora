package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.admin.NomenclatureAdminRepository
import ru.transora.app.domain.DomainRuleViolation
import ru.transora.app.scheduling.TripRepository
import ru.transora.sales.domain.RefundPreview
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class RefundCalculator(
    private val commercePolicyRepository: CommercePolicyRepository,
    private val commercePolicyResolver: CommercePolicyResolver,
    private val policyPriceCalculator: PolicyPriceCalculator,
    private val tripRepository: TripRepository,
    private val nomenclatureAdminRepository: NomenclatureAdminRepository,
) {
    fun preview(ticketPriceCents: Long, tripId: UUID, refundAt: Instant = Clock.systemUTC().instant()): RefundPreview {
        val policyId = commercePolicyResolver.resolveTicketRefundPolicyId(tripId, refundAt)
        return preview(ticketPriceCents, tripId, refundAt, policyId)
    }

    fun previewForNomenclature(
        itemPriceCents: Long,
        tripId: UUID,
        nomenclatureItemId: UUID,
        refundAt: Instant = Clock.systemUTC().instant(),
    ): RefundPreview {
        val item = nomenclatureAdminRepository.findById(nomenclatureItemId)
            ?: throw NoSuchElementException("Nomenclature item $nomenclatureItemId was not found")
        if (!item.refundAllowed) {
            return RefundPreview(
                penaltyPercent = BigDecimal.ZERO,
                penaltyCents = itemPriceCents,
                serviceFeeCents = 0,
                refundCents = 0,
                refundAllowed = false,
            )
        }
        val policyId = item.refundPolicyId
            ?: throw DomainRuleViolation("refund_policy_id is required when refund is allowed")
        return preview(itemPriceCents, tripId, refundAt, policyId)
    }

    fun preview(
        ticketPriceCents: Long,
        tripId: UUID,
        refundAt: Instant,
        policyId: UUID,
    ): RefundPreview {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val (policy, tier) = commercePolicyResolver.resolveRefundPolicyWithTier(policyId, tripId, refundAt)
        val resolvedTier = tier
            ?: throw DomainRuleViolation("No refund threshold applies for this departure time")

        if (!resolvedTier.refundAllowed) {
            return RefundPreview(
                penaltyPercent = resolvedTier.penaltyPercent,
                penaltyCents = ticketPriceCents,
                serviceFeeCents = commissionCents(policy, resolvedTier, ticketPriceCents, 0),
                refundCents = 0,
                refundAllowed = false,
                policyId = policyId,
            )
        }

        val penaltyCents = calculatePenaltyCents(ticketPriceCents, resolvedTier.penaltyPercent)
        val grossRefund = (ticketPriceCents - penaltyCents).coerceAtLeast(0)
        val commission = commissionCents(policy, resolvedTier, ticketPriceCents, grossRefund)
        val refundCents = (grossRefund - commission).coerceAtLeast(0)

        return RefundPreview(
            penaltyPercent = resolvedTier.penaltyPercent,
            penaltyCents = penaltyCents,
            serviceFeeCents = commission,
            refundCents = refundCents,
            refundAllowed = true,
            policyId = policyId,
        )
    }

    private fun commissionCents(
        policy: CommercePolicyRow,
        tier: CommercePolicyTierRow,
        routePriceCents: Long,
        refundAmountCents: Long,
    ): Long {
        val hasPolicyPricing = policy.nomenclatureItemId != null ||
            policy.pricingMode != ru.transora.sales.domain.PolicyPricingMode.FROM_NOMENCLATURE ||
            policy.fixedPriceCents != null ||
            policy.percentValue != null
        if (!hasPolicyPricing) {
            return commercePolicyRepository.serviceFeeCents(policy.id)
        }
        return runCatching {
            policyPriceCalculator.unitPriceCents(policy, tier, routePriceCents, refundAmountCents)
        }.getOrElse { commercePolicyRepository.serviceFeeCents(policy.id) }
    }

    private fun calculatePenaltyCents(priceCents: Long, penaltyPercent: BigDecimal): Long =
        BigDecimal(priceCents)
            .multiply(penaltyPercent)
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            .longValueExact()
}
