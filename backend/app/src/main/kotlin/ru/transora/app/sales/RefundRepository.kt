package ru.transora.app.sales

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.transora.sales.domain.Refund
import ru.transora.sales.domain.RefundPolicyTier
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class RefundRepository(
    private val jdbc: JdbcTemplate,
) {
    fun insert(refund: Refund) {
        jdbc.update(
            """
            INSERT INTO sales.refunds (
                id, ticket_id, policy_id, penalty_percent, penalty_cents,
                service_fee_cents, refund_cents, refund_type, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            refund.id,
            refund.ticketId,
            refund.policyId,
            refund.penaltyPercent,
            refund.penaltyCents,
            refund.serviceFeeCents,
            refund.refundCents,
            refund.refundType.name,
            Timestamp.from(refund.createdAt),
        )
    }

    fun findTiersByPolicyId(policyId: UUID): List<RefundPolicyTier> =
        jdbc.query(
            """
            SELECT * FROM sales.policy_tiers
            WHERE policy_id = ?
            ORDER BY sort_order
            """.trimIndent(),
            { rs, _ -> rs.toTier() },
            policyId,
        )

    fun serviceFeeCents(policyId: UUID): Long =
        jdbc.queryForObject(
            "SELECT service_fee_cents FROM sales.commerce_policies WHERE id = ?",
            Long::class.java,
            policyId,
        ) ?: 0L

    fun updateFiscalReceiptId(refundId: UUID, fiscalReceiptId: UUID) {
        jdbc.update(
            "UPDATE sales.refunds SET fiscal_receipt_id = ? WHERE id = ?",
            fiscalReceiptId,
            refundId,
        )
    }

    fun findFiscalReceiptId(refundId: UUID): UUID? =
        jdbc.queryForObject(
            "SELECT fiscal_receipt_id FROM sales.refunds WHERE id = ?",
            UUID::class.java,
            refundId,
        )

    private fun ResultSet.toTier(): RefundPolicyTier =
        RefundPolicyTier(
            id = getObject("id", UUID::class.java),
            policyId = getObject("policy_id", UUID::class.java),
            hoursBeforeMin = getObject("hours_before_min")?.let { (it as Number).toInt() },
            hoursBeforeMax = getObject("hours_before_max")?.let { (it as Number).toInt() },
            penaltyPercent = getBigDecimal("penalty_percent"),
            refundAllowed = getBoolean("refund_allowed"),
            sortOrder = getInt("sort_order"),
        )

    companion object {
        val DEFAULT_POLICY_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
    }
}
