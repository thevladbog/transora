package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.CommercePolicyType
import ru.transora.sales.domain.PolicyPricingMode
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

@Repository
class CommercePolicyRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findById(id: UUID): CommercePolicyRow? =
        jdbc.query(
            """
            SELECT cp.*, n.code AS nomenclature_code, n.name AS nomenclature_name
            FROM sales.commerce_policies cp
            LEFT JOIN sales.nomenclature_items n ON n.id = cp.nomenclature_item_id
            WHERE cp.id = ?
            """.trimIndent(),
            { rs, _ -> rs.toPolicy() },
            id,
        ).firstOrNull()

    fun findTiersByPolicyId(policyId: UUID): List<CommercePolicyTierRow> =
        jdbc.query(
            """
            SELECT * FROM sales.policy_tiers
            WHERE policy_id = ?
            ORDER BY sort_order
            """.trimIndent(),
            { rs, _ -> rs.toTier() },
            policyId,
        )

    fun listActiveRoutePolicies(routeId: UUID): List<RoutePolicyBinding> =
        jdbc.query(
            """
            SELECT rcp.priority, cp.*, n.code AS nomenclature_code, n.name AS nomenclature_name
            FROM sales.route_commerce_policies rcp
            JOIN sales.commerce_policies cp ON cp.id = rcp.policy_id
            LEFT JOIN sales.nomenclature_items n ON n.id = cp.nomenclature_item_id
            WHERE rcp.route_id = ? AND cp.is_active = TRUE
            ORDER BY rcp.priority
            """.trimIndent(),
            { rs, _ ->
                RoutePolicyBinding(
                    priority = rs.getInt("priority"),
                    policy = rs.toPolicy(),
                )
            },
            routeId,
        )

    fun serviceFeeCents(policyId: UUID): Long =
        jdbc.queryForObject(
            "SELECT service_fee_cents FROM sales.commerce_policies WHERE id = ?",
            Long::class.java,
            policyId,
        ) ?: 0L

    private fun ResultSet.toPolicy(): CommercePolicyRow =
        CommercePolicyRow(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            isActive = getBoolean("is_active"),
            serviceFeeCents = getLong("service_fee_cents"),
            policyType = CommercePolicyType.valueOf(getString("policy_type")),
            nomenclatureItemId = getObject("nomenclature_item_id") as UUID?,
            nomenclatureCode = getString("nomenclature_code"),
            nomenclatureName = getString("nomenclature_name"),
            isMandatory = getBoolean("is_mandatory"),
            pricingMode = PolicyPricingMode.valueOf(getString("pricing_mode")),
            fixedPriceCents = getObject("fixed_price_cents") as Long?,
            percentValue = getBigDecimal("percent_value"),
            percentBasis = getString("percent_basis"),
            minPriceCents = getObject("min_price_cents") as Long?,
            maxPriceCents = getObject("max_price_cents") as Long?,
        )

    private fun ResultSet.toTier(): CommercePolicyTierRow =
        CommercePolicyTierRow(
            id = getObject("id", UUID::class.java),
            policyId = getObject("policy_id", UUID::class.java),
            hoursBeforeMin = getObject("hours_before_min")?.let { (it as Number).toInt() },
            hoursBeforeMax = getObject("hours_before_max")?.let { (it as Number).toInt() },
            penaltyPercent = getBigDecimal("penalty_percent"),
            refundAllowed = getBoolean("refund_allowed"),
            sortOrder = getInt("sort_order"),
            fixedPriceCents = getObject("fixed_price_cents") as Long?,
            percentValue = getBigDecimal("percent_value"),
        )
}

data class CommercePolicyRow(
    val id: UUID,
    val name: String,
    val isActive: Boolean,
    val serviceFeeCents: Long,
    val policyType: CommercePolicyType,
    val nomenclatureItemId: UUID?,
    val nomenclatureCode: String?,
    val nomenclatureName: String?,
    val isMandatory: Boolean,
    val pricingMode: PolicyPricingMode,
    val fixedPriceCents: Long?,
    val percentValue: BigDecimal?,
    val percentBasis: String?,
    val minPriceCents: Long?,
    val maxPriceCents: Long?,
)

data class CommercePolicyTierRow(
    val id: UUID,
    val policyId: UUID,
    val hoursBeforeMin: Int?,
    val hoursBeforeMax: Int?,
    val penaltyPercent: BigDecimal,
    val refundAllowed: Boolean,
    val sortOrder: Int,
    val fixedPriceCents: Long?,
    val percentValue: BigDecimal?,
)

data class RoutePolicyBinding(
    val priority: Int,
    val policy: CommercePolicyRow,
)
