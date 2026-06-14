package ru.transora.app.sales

import org.springframework.stereotype.Service
import ru.transora.app.admin.TariffProfileAdminRepository
import ru.transora.app.scheduling.TripRepository
import ru.transora.sales.domain.CommercePolicyType
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ResolvedPolicy(
    val policyId: UUID,
    val type: CommercePolicyType,
    val mandatory: Boolean,
    val nomenclatureItemId: UUID?,
    val nomenclatureCode: String?,
    val nomenclatureName: String?,
    val unitPriceCents: Long,
    val policyName: String,
    val tier: CommercePolicyTierRow?,
)

data class ApplicablePoliciesResult(
    val mandatory: List<ResolvedPolicy>,
    val optional: List<ResolvedPolicy>,
)

@Service
class CommercePolicyResolver(
    private val commercePolicyRepository: CommercePolicyRepository,
    private val tripRepository: TripRepository,
    private val tariffProfileAdminRepository: TariffProfileAdminRepository,
    private val policyPriceCalculator: PolicyPriceCalculator,
) {
    fun resolveApplicableSalePolicies(
        tripId: UUID,
        routePriceCents: Long,
        at: Instant = Instant.now(),
    ): ApplicablePoliciesResult {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val routeId = trip.routeId ?: return ApplicablePoliciesResult(emptyList(), emptyList())
        val hoursUntilDeparture = hoursUntilDeparture(at, trip.departureTime)
        val bindings = commercePolicyRepository.listActiveRoutePolicies(routeId)
        val mandatory = mutableListOf<ResolvedPolicy>()
        val optional = mutableListOf<ResolvedPolicy>()
        bindings.forEach { binding ->
            if (binding.policy.policyType != CommercePolicyType.SALE) return@forEach
            val tiers = commercePolicyRepository.findTiersByPolicyId(binding.policy.id)
            val tier = resolveTier(tiers, hoursUntilDeparture) ?: return@forEach
            val resolved = toResolved(binding.policy, tier, routePriceCents)
            if (binding.policy.isMandatory) {
                mandatory += resolved
            } else {
                optional += resolved
            }
        }
        return ApplicablePoliciesResult(mandatory = mandatory, optional = optional)
    }

    fun resolveTicketRefundPolicyId(tripId: UUID, at: Instant = Instant.now()): UUID {
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        trip.routeId?.let { routeId ->
            val hoursUntilDeparture = hoursUntilDeparture(at, trip.departureTime)
            commercePolicyRepository.listActiveRoutePolicies(routeId).forEach { binding ->
                if (binding.policy.policyType != CommercePolicyType.REFUND) return@forEach
                val tiers = commercePolicyRepository.findTiersByPolicyId(binding.policy.id)
                if (resolveTier(tiers, hoursUntilDeparture) != null) {
                    return binding.policy.id
                }
            }
        }
        trip.routeId?.let { routeId ->
            tariffProfileAdminRepository.findActiveByRouteId(routeId)?.refundPolicyId?.let { return it }
        }
        return RefundRepository.DEFAULT_POLICY_ID
    }

    fun resolveRefundPolicyWithTier(
        policyId: UUID,
        tripId: UUID,
        at: Instant,
    ): Pair<CommercePolicyRow, CommercePolicyTierRow?> {
        val policy = commercePolicyRepository.findById(policyId)
            ?: throw NoSuchElementException("Policy $policyId was not found")
        val trip = tripRepository.findById(tripId)
            ?: throw NoSuchElementException("Trip $tripId was not found")
        val tiers = commercePolicyRepository.findTiersByPolicyId(policyId)
        val hoursUntilDeparture = hoursUntilDeparture(at, trip.departureTime)
        val tier = resolveTier(tiers, hoursUntilDeparture)
        return policy to tier
    }

    fun isRouteSalePolicyNomenclature(tripId: UUID, nomenclatureItemId: UUID): Boolean {
        val trip = tripRepository.findById(tripId) ?: return false
        val routeId = trip.routeId ?: return false
        return commercePolicyRepository.listActiveRoutePolicies(routeId).any { binding ->
            binding.policy.policyType == CommercePolicyType.SALE &&
                binding.policy.nomenclatureItemId == nomenclatureItemId
        }
    }

    private fun toResolved(
        policy: CommercePolicyRow,
        tier: CommercePolicyTierRow,
        routePriceCents: Long,
    ): ResolvedPolicy =
        ResolvedPolicy(
            policyId = policy.id,
            type = policy.policyType,
            mandatory = policy.isMandatory,
            nomenclatureItemId = policy.nomenclatureItemId,
            nomenclatureCode = policy.nomenclatureCode,
            nomenclatureName = policy.nomenclatureName,
            unitPriceCents = policyPriceCalculator.unitPriceCents(policy, tier, routePriceCents),
            policyName = policy.name,
            tier = tier,
        )

    private fun hoursUntilDeparture(at: Instant, departureTime: Instant): Double =
        Duration.between(at, departureTime).toMinutes() / 60.0

    private fun resolveTier(tiers: List<CommercePolicyTierRow>, hoursUntilDeparture: Double): CommercePolicyTierRow? =
        tiers.sortedBy { it.sortOrder }.firstOrNull { tier ->
            val minOk = tier.hoursBeforeMin?.let { hoursUntilDeparture >= it } ?: true
            val maxOk = tier.hoursBeforeMax?.let { hoursUntilDeparture < it } ?: true
            minOk && maxOk
        }
}
