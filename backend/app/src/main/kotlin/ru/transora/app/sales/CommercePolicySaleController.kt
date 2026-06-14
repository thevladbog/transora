package ru.transora.app.sales

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.transora.app.iam.security.RequirePermission
import ru.transora.iam.permissions.Permissions
import java.util.UUID

@RestController
@RequestMapping("/api/sales")
@Tag(name = "Commerce Policy Sales")
class CommercePolicySaleController(
    private val commercePolicyResolver: CommercePolicyResolver,
    private val tariffCalculator: TariffCalculator,
) {
    @GetMapping("/applicable-policies")
    @RequirePermission(Permissions.TICKETS_SELL)
    @Operation(summary = "List mandatory and optional sale policies for a trip segment")
    fun applicablePolicies(
        @RequestParam tripId: UUID,
        @RequestParam @Min(1) fromStop: Int,
        @RequestParam @Min(1) toStop: Int,
    ): ApplicablePoliciesResponse {
        val routePrice = tariffCalculator.calculate(tripId, fromStop, toStop).priceCents
        val result = commercePolicyResolver.resolveApplicableSalePolicies(tripId, routePrice)
        return ApplicablePoliciesResponse(
            mandatory = result.mandatory.map { it.toResponse() },
            optional = result.optional.map { it.toResponse() },
        )
    }

    private fun ResolvedPolicy.toResponse() = ApplicablePolicyItem(
        policyId = policyId.toString(),
        policyName = policyName,
        nomenclatureItemId = nomenclatureItemId?.toString(),
        nomenclatureCode = nomenclatureCode,
        nomenclatureName = nomenclatureName,
        quantity = 1,
        unitPriceCents = unitPriceCents,
        mandatory = mandatory,
    )
}

data class ApplicablePoliciesResponse(
    val mandatory: List<ApplicablePolicyItem>,
    val optional: List<ApplicablePolicyItem>,
)

data class ApplicablePolicyItem(
    val policyId: String,
    val policyName: String,
    val nomenclatureItemId: String?,
    val nomenclatureCode: String?,
    val nomenclatureName: String?,
    val quantity: Int,
    val unitPriceCents: Long,
    val mandatory: Boolean,
)
