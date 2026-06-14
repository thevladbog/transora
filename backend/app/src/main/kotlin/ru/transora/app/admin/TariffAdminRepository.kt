package ru.transora.app.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class TariffAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun list(): List<TariffRow> =
        jdbc.query(
            "SELECT * FROM sales.tariffs ORDER BY route_number, from_stop_order",
            { rs, _ -> rs.toRow() },
        )

    fun findById(id: UUID): TariffRow? =
        jdbc.query("SELECT * FROM sales.tariffs WHERE id = ?", { rs, _ -> rs.toRow() }, id).firstOrNull()

    fun insert(row: TariffRow) {
        jdbc.update(
            """
            INSERT INTO sales.tariffs (
                id, route_number, from_stop_order, to_stop_order, price_cents, is_active, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.routeNumber,
            row.fromStopOrder,
            row.toStopOrder,
            row.priceCents,
            row.isActive,
            Timestamp.from(row.createdAt),
        )
    }

    fun update(row: TariffRow) {
        jdbc.update(
            """
            UPDATE sales.tariffs
            SET route_number = ?, from_stop_order = ?, to_stop_order = ?, price_cents = ?, is_active = ?
            WHERE id = ?
            """.trimIndent(),
            row.routeNumber,
            row.fromStopOrder,
            row.toStopOrder,
            row.priceCents,
            row.isActive,
            row.id,
        )
    }

    fun delete(id: UUID): Int = jdbc.update("DELETE FROM sales.tariffs WHERE id = ?", id)

    private fun ResultSet.toRow(): TariffRow =
        TariffRow(
            id = getObject("id", UUID::class.java),
            routeNumber = getString("route_number"),
            fromStopOrder = getInt("from_stop_order"),
            toStopOrder = getInt("to_stop_order"),
            priceCents = getLong("price_cents"),
            isActive = getBoolean("is_active"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
}

data class TariffRow(
    val id: UUID,
    val routeNumber: String,
    val fromStopOrder: Int,
    val toStopOrder: Int,
    val priceCents: Long,
    val isActive: Boolean,
    val createdAt: Instant,
)

@Repository
class CommercePolicyAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listPolicies(policyType: String? = null): List<CommercePolicyRow> {
        val sql = if (policyType != null) {
            """
            SELECT cp.*, n.code AS nomenclature_code, n.name AS nomenclature_name
            FROM sales.commerce_policies cp
            LEFT JOIN sales.nomenclature_items n ON n.id = cp.nomenclature_item_id
            WHERE cp.policy_type = ?
            ORDER BY cp.name
            """.trimIndent()
        } else {
            """
            SELECT cp.*, n.code AS nomenclature_code, n.name AS nomenclature_name
            FROM sales.commerce_policies cp
            LEFT JOIN sales.nomenclature_items n ON n.id = cp.nomenclature_item_id
            ORDER BY cp.name
            """.trimIndent()
        }
        return if (policyType != null) {
            jdbc.query(sql, { rs, _ -> rs.toPolicy() }, policyType)
        } else {
            jdbc.query(sql, { rs, _ -> rs.toPolicy() })
        }
    }

    fun findPolicy(id: UUID): CommercePolicyRow? =
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

    fun insertPolicy(row: CommercePolicyRow) {
        jdbc.update(
            """
            INSERT INTO sales.commerce_policies (
                id, name, is_active, service_fee_cents, created_at, policy_type,
                nomenclature_item_id, is_mandatory, pricing_mode, fixed_price_cents,
                percent_value, percent_basis, min_price_cents, max_price_cents
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.name,
            row.isActive,
            row.serviceFeeCents,
            Timestamp.from(row.createdAt),
            row.policyType,
            row.nomenclatureItemId,
            row.isMandatory,
            row.pricingMode,
            row.fixedPriceCents,
            row.percentValue,
            row.percentBasis,
            row.minPriceCents,
            row.maxPriceCents,
        )
    }

    fun updatePolicy(row: CommercePolicyRow) {
        jdbc.update(
            """
            UPDATE sales.commerce_policies
            SET name = ?, is_active = ?, service_fee_cents = ?, policy_type = ?,
                nomenclature_item_id = ?, is_mandatory = ?, pricing_mode = ?,
                fixed_price_cents = ?, percent_value = ?, percent_basis = ?,
                min_price_cents = ?, max_price_cents = ?
            WHERE id = ?
            """.trimIndent(),
            row.name,
            row.isActive,
            row.serviceFeeCents,
            row.policyType,
            row.nomenclatureItemId,
            row.isMandatory,
            row.pricingMode,
            row.fixedPriceCents,
            row.percentValue,
            row.percentBasis,
            row.minPriceCents,
            row.maxPriceCents,
            row.id,
        )
    }

    fun deletePolicy(id: UUID): Int = jdbc.update("DELETE FROM sales.commerce_policies WHERE id = ?", id)

    fun countNomenclatureReferences(policyId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.nomenclature_items WHERE refund_policy_id = ?",
            Int::class.java,
            policyId,
        ) ?: 0

    fun countTariffProfileReferences(policyId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.tariff_profiles WHERE refund_policy_id = ?",
            Int::class.java,
            policyId,
        ) ?: 0

    fun countRouteReferences(policyId: UUID): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.route_commerce_policies WHERE policy_id = ?",
            Int::class.java,
            policyId,
        ) ?: 0

    fun listTiers(policyId: UUID): List<CommercePolicyTierRow> =
        jdbc.query(
            """
            SELECT * FROM sales.policy_tiers
            WHERE policy_id = ?
            ORDER BY sort_order
            """.trimIndent(),
            { rs, _ -> rs.toTier() },
            policyId,
        )

    fun replaceTiers(policyId: UUID, tiers: List<CommercePolicyTierRow>) {
        jdbc.update("DELETE FROM sales.policy_tiers WHERE policy_id = ?", policyId)
        tiers.forEach { tier ->
            jdbc.update(
                """
                INSERT INTO sales.policy_tiers (
                    id, policy_id, hours_before_min, hours_before_max,
                    penalty_percent, refund_allowed, sort_order,
                    fixed_price_cents, percent_value
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                tier.id,
                policyId,
                tier.hoursBeforeMin,
                tier.hoursBeforeMax,
                tier.penaltyPercent,
                tier.refundAllowed,
                tier.sortOrder,
                tier.fixedPriceCents,
                tier.percentValue,
            )
        }
    }

    private fun ResultSet.toPolicy(): CommercePolicyRow =
        CommercePolicyRow(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            isActive = getBoolean("is_active"),
            serviceFeeCents = getLong("service_fee_cents"),
            createdAt = getTimestamp("created_at").toInstant(),
            policyType = getString("policy_type"),
            nomenclatureItemId = getObject("nomenclature_item_id") as UUID?,
            nomenclatureCode = getString("nomenclature_code"),
            nomenclatureName = getString("nomenclature_name"),
            isMandatory = getBoolean("is_mandatory"),
            pricingMode = getString("pricing_mode"),
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
    val createdAt: Instant,
    val policyType: String = "REFUND",
    val nomenclatureItemId: UUID? = null,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val isMandatory: Boolean = false,
    val pricingMode: String = "FROM_NOMENCLATURE",
    val fixedPriceCents: Long? = null,
    val percentValue: java.math.BigDecimal? = null,
    val percentBasis: String? = null,
    val minPriceCents: Long? = null,
    val maxPriceCents: Long? = null,
)

data class CommercePolicyTierRow(
    val id: UUID,
    val policyId: UUID,
    val hoursBeforeMin: Int?,
    val hoursBeforeMax: Int?,
    val penaltyPercent: java.math.BigDecimal,
    val refundAllowed: Boolean,
    val sortOrder: Int,
    val fixedPriceCents: Long? = null,
    val percentValue: java.math.BigDecimal? = null,
)

@Repository
class RouteCommercePolicyRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listByRouteId(routeId: UUID): List<RouteCommercePolicyRow> =
        jdbc.query(
            """
            SELECT rcp.route_id, rcp.policy_id, rcp.priority,
                   cp.name AS policy_name, cp.policy_type, cp.is_active
            FROM sales.route_commerce_policies rcp
            JOIN sales.commerce_policies cp ON cp.id = rcp.policy_id
            WHERE rcp.route_id = ?
            ORDER BY rcp.priority
            """.trimIndent(),
            { rs, _ ->
                RouteCommercePolicyRow(
                    routeId = rs.getObject("route_id", UUID::class.java),
                    policyId = rs.getObject("policy_id", UUID::class.java),
                    priority = rs.getInt("priority"),
                    policyName = rs.getString("policy_name"),
                    policyType = rs.getString("policy_type"),
                    policyActive = rs.getBoolean("is_active"),
                )
            },
            routeId,
        )

    fun replaceRoutePolicies(routeId: UUID, policyIdsInOrder: List<UUID>) {
        jdbc.update("DELETE FROM sales.route_commerce_policies WHERE route_id = ?", routeId)
        policyIdsInOrder.forEachIndexed { index, policyId ->
            jdbc.update(
                """
                INSERT INTO sales.route_commerce_policies (route_id, policy_id, priority)
                VALUES (?, ?, ?)
                """.trimIndent(),
                routeId,
                policyId,
                index + 1,
            )
        }
    }
}

data class RouteCommercePolicyRow(
    val routeId: UUID,
    val policyId: UUID,
    val priority: Int,
    val policyName: String,
    val policyType: String,
    val policyActive: Boolean,
)
