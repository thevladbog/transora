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
class RefundPolicyAdminRepository(
    private val jdbc: JdbcTemplate,
) {
    fun listPolicies(): List<RefundPolicyRow> =
        jdbc.query("SELECT * FROM sales.refund_policies ORDER BY name", { rs, _ -> rs.toPolicy() })

    fun findPolicy(id: UUID): RefundPolicyRow? =
        jdbc.query("SELECT * FROM sales.refund_policies WHERE id = ?", { rs, _ -> rs.toPolicy() }, id).firstOrNull()

    fun insertPolicy(row: RefundPolicyRow) {
        jdbc.update(
            """
            INSERT INTO sales.refund_policies (id, name, is_active, service_fee_cents, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            row.id,
            row.name,
            row.isActive,
            row.serviceFeeCents,
            Timestamp.from(row.createdAt),
        )
    }

    fun updatePolicy(row: RefundPolicyRow) {
        jdbc.update(
            """
            UPDATE sales.refund_policies
            SET name = ?, is_active = ?, service_fee_cents = ?
            WHERE id = ?
            """.trimIndent(),
            row.name,
            row.isActive,
            row.serviceFeeCents,
            row.id,
        )
    }

    fun deletePolicy(id: UUID): Int = jdbc.update("DELETE FROM sales.refund_policies WHERE id = ?", id)

    fun listTiers(policyId: UUID): List<RefundPolicyTierRow> =
        jdbc.query(
            """
            SELECT * FROM sales.refund_policy_tiers
            WHERE policy_id = ?
            ORDER BY sort_order
            """.trimIndent(),
            { rs, _ -> rs.toTier() },
            policyId,
        )

    fun replaceTiers(policyId: UUID, tiers: List<RefundPolicyTierRow>) {
        jdbc.update("DELETE FROM sales.refund_policy_tiers WHERE policy_id = ?", policyId)
        tiers.forEach { tier ->
            jdbc.update(
                """
                INSERT INTO sales.refund_policy_tiers (
                    id, policy_id, hours_before_min, hours_before_max,
                    penalty_percent, refund_allowed, sort_order
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                tier.id,
                policyId,
                tier.hoursBeforeMin,
                tier.hoursBeforeMax,
                tier.penaltyPercent,
                tier.refundAllowed,
                tier.sortOrder,
            )
        }
    }

    private fun ResultSet.toPolicy(): RefundPolicyRow =
        RefundPolicyRow(
            id = getObject("id", UUID::class.java),
            name = getString("name"),
            isActive = getBoolean("is_active"),
            serviceFeeCents = getLong("service_fee_cents"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.toTier(): RefundPolicyTierRow =
        RefundPolicyTierRow(
            id = getObject("id", UUID::class.java),
            policyId = getObject("policy_id", UUID::class.java),
            hoursBeforeMin = getObject("hours_before_min")?.let { (it as Number).toInt() },
            hoursBeforeMax = getObject("hours_before_max")?.let { (it as Number).toInt() },
            penaltyPercent = getBigDecimal("penalty_percent"),
            refundAllowed = getBoolean("refund_allowed"),
            sortOrder = getInt("sort_order"),
        )
}

data class RefundPolicyRow(
    val id: UUID,
    val name: String,
    val isActive: Boolean,
    val serviceFeeCents: Long,
    val createdAt: Instant,
)

data class RefundPolicyTierRow(
    val id: UUID,
    val policyId: UUID,
    val hoursBeforeMin: Int?,
    val hoursBeforeMax: Int?,
    val penaltyPercent: java.math.BigDecimal,
    val refundAllowed: Boolean,
    val sortOrder: Int,
)
